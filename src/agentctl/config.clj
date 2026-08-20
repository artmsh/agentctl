(ns agentctl.config
  "Reader + normalizer for ~/.config/agents.edn.

   The declared file is convenience-shaped (loose, terse). Everything downstream
   consumes the normalized shape produced here, so adapters never re-interpret
   surface syntax."
  (:require [agentctl.refs :as refs]
            [agentctl.util :as u]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(def all-tools [:claude :codex :pi :omp])

(def capabilities
  "Which resource kinds each tool can be provisioned with."
  {:claude #{:settings :mcps :skills :memory :projects :permissions}
   :codex  #{:settings :mcps :skills :memory :projects :providers}
   :pi     #{:settings :mcps :skills :memory :providers :projects}
   :omp    #{:settings :mcps :skills :memory :providers}})

(defn supports? [tool kind] (contains? (get capabilities tool #{}) kind))

(defn tools-for [kind] (filterv #(supports? % kind) all-tools))

(def default-path (str u/home "/.config/agents.edn"))

;; ---------------------------------------------------------------- findings

(defn finding [level where message & [data]]
  (u/prune-nils {:level level :where (vec where) :message message :data data}))

;; ---------------------------------------------------------------- helpers

(defn- as-set [v]
  (cond
    (nil? v) nil
    (keyword? v) #{v}
    (coll? v) (set v)
    :else #{(keyword (str v))}))

(defn- tool-selection
  "Resolve a declared :tools/:for selector into a concrete set of tools."
  [decl kind]
  (let [sel (or (:tools decl) (:for decl))]
    (cond
      (nil? sel) (set (tools-for kind))
      (= :all sel) (set (tools-for kind))
      :else (set (filter #(supports? % kind) (as-set sel))))))

(defn- project-path
  "`:path` is the whole location; `:parent` is the directory it sits in, for the
   common case of many projects under one workspace root."
  [id decl]
  (u/abs-path (or (:path decl)
                  (when-let [p (:parent decl)] (str (u/expand (str p)) "/" (name id)))
                  (str "~/projects/" (name id)))))

(defn- as-vec [v]
  (cond (nil? v) nil (sequential? v) (vec v) (set? v) (vec (sort-by str v)) :else [v]))

;; ---------------------------------------------------------------- $let

(def let-key :#let)

(defn- let-name
  "The `name` in a `$name` reference, or nil."
  [x]
  (when (symbol? x)
    (let [s (name x)]
      (when (str/starts-with? s "$") (not-empty (subs s 1))))))

(defn- scalar->str [v]
  (cond (string? v) v (keyword? v) (name v) (symbol? v) (name v)
        (or (number? v) (boolean? v)) (str v) :else nil))

(defn- substitute
  "Replace `$name` with its binding: as a bare symbol, and inside strings so a
   binding can be spliced into a path or a command line."
  [form bindings]
  (let [interp (into {} (keep (fn [[k v]] (when-let [s (scalar->str v)] [k s]))) bindings)
        in-string (fn [s]
                    (str/replace s #"\$\{?([A-Za-z_][A-Za-z0-9_-]*)\}?"
                                 (fn [[whole n]] (get interp n whole))))]
    (walk/postwalk
     (fn [x]
       (cond
         (let-name x) (let [n (let-name x)]
                        (if (contains? bindings n) (get bindings n) x))
         (and (string? x) (seq interp) (str/includes? x "$")) (in-string x)
         :else x))
     form)))

(defn expand-lets
  "Resolve root-level `:#let` bindings into the `$name` references that use them.

   Root level only: `:#let` nested inside a project or an mcp is a config error,
   not a local scope. An unbound `$NAME` is left untouched — that spelling is
   the environment-variable reference, and shadowing it here would be surprising."
  [raw]
  (let [bindings (into {} (map (fn [[k v]] [(u/kw->str k) v])) (get raw let-key))
        body (dissoc raw let-key)
        nested (volatile! false)
        _ (walk/postwalk (fn [x] (when (and (map? x) (contains? x let-key)) (vreset! nested true)) x) body)
        self (for [[k v] bindings
                   n (distinct (keep let-name (tree-seq coll? seq v)))
                   :when (contains? bindings n)]
               (finding :error [let-key (keyword k)]
                        (str "$" n " — a :#let value cannot reference another binding")))
        errors (concat (when @nested
                         [(finding :error [let-key]
                                   ":#let is only valid at the top level of agents.edn")])
                       self)
        ;; a lowercase $name that resolves to nothing is far more likely a typo
        ;; than a deliberate env var, which is conventionally SHOUTED
        unbound (when (empty? errors)
                  (for [n (distinct (keep let-name (tree-seq coll? seq body)))
                        :when (and (not (contains? bindings n)) (not= n (str/upper-case n)))]
                    (finding :warn [let-key]
                             (str "$" n " is not a :#let binding — resolved as env var $" n))))]
    {:raw (if (seq errors) body (substitute body bindings))
     :findings (vec (concat errors unbound))}))

;; ---------------------------------------------------------------- permissions

(def ^:private permission-buckets [:allow :ask :deny])

(defn- mcp-rules [spec]
  (mapcat (fn [[server tools]]
            (let [s (u/kw->str server)]
              (if (or (= :all tools) (true? tools) (nil? tools))
                [(str "mcp__" s)]
                (map #(str "mcp__" s "__" (u/kw->str %)) (as-vec tools)))))
          spec))

(defn- perm-rules
  "Compile the permission mini-DSL into the rule strings Claude Code stores.

     {:Bash [\"bb:*\"]}               -> \"Bash(bb:*)\"
     {:Bash :all}                     -> \"Bash\"
     {:Mcp {:clj-repl #{list_repls}}} -> \"mcp__clj-repl__list_repls\"
     {:Mcp {:clj-repl :all}}          -> \"mcp__clj-repl\"

   A flat collection of strings is already in that form and passes through."
  [decl]
  (cond
    (nil? decl) nil
    (map? decl) (vec (sort (mapcat
                            (fn [[tool spec]]
                              (let [t (u/kw->str tool)]
                                (if (= "mcp" (str/lower-case t))
                                  (mcp-rules spec)
                                  (cond
                                    (or (= :all spec) (true? spec)) [t]
                                    (or (string? spec) (symbol? spec)) [(str t "(" spec ")")]
                                    :else (map #(str t "(" (u/kw->str %) ")") (as-vec spec))))))
                            decl)))
    (coll? decl) (vec (sort (map u/kw->str decl)))
    :else [(u/kw->str decl)]))

(defn- norm-permissions [decl]
  (when (map? decl)
    (not-empty
     (merge (apply dissoc decl permission-buckets)
            (into {} (keep (fn [b] (when-let [rules (not-empty (perm-rules (get decl b)))]
                                     [b rules])))
                  permission-buckets)))))

;; ---------------------------------------------------------------- normalize

(declare norm-mcp norm-provider norm-skill)

(defn- norm-per-tool
  "Normalize the per-tool override map with the same normalizer as the base."
  [id decl f]
  (not-empty
   (into {} (map (fn [[t override]] [t (dissoc (f id override) :id :kind :tools :per-tool)]))
         (:per-tool decl))))

(defn- mcp-decl
  "A bare string is the shorthand every stdio server ends up wanting: the thing
   to run. A string that is plainly a URL is the http spelling of the same idea."
  [decl]
  (cond
    (map? decl) decl
    (string? decl) (if (re-find #"^https?://" decl) {:url decl} {:cmd decl})
    (nil? decl) {}
    :else {:cmd (str decl)}))

(defn- norm-mcp [id decl]
  (let [decl (mcp-decl decl)
        env (into {} (map (fn [[k v]] [k (refs/parse v)])) (:env decl))
        headers (into {} (map (fn [[k v]] [k (refs/parse v)])) (:headers decl))
        url (:url decl)
        ;; :cmd is one shell-ish line; :command/:args is the exploded form
        tokens (u/split-cmd (:cmd decl))
        cmd (or (:command decl) (first tokens))
        transport (or (:transport decl) (:type decl))]
    (u/prune-nils
     {:id id
      :kind :mcps
      :transport (cond transport (keyword (u/kw->str transport))
                       url :http
                       :else :stdio)
      :command (some-> cmd u/expand)
      :args (mapv (comp str u/expand) (concat (when-not (:command decl) (rest tokens)) (:args decl)))
      :env (not-empty env)
      :url url
      :headers (not-empty headers)
      :bearer-token-env (:bearer-token-env decl)
      :cwd (some-> (:cwd decl) u/abs-path)
      :enabled (get decl :enabled true)
      :scope (some-> (:scope decl) u/kw->str keyword)
      :extra (:extra decl)
      :per-tool (norm-per-tool id decl norm-mcp)
      :tools (tool-selection decl :mcps)})))

(defn- norm-skill [id decl]
  (let [decl (or decl {})]
    (u/prune-nils
     {:id id
      :kind :skills
      :from (:from decl)
      :path (some-> (:path decl) u/abs-path)
      :subdir (:subdir decl)
      :mode (keyword (u/kw->str (or (:mode decl) :symlink)))
      :scope (some-> (:scope decl) u/kw->str keyword)
      :per-tool (norm-per-tool id decl norm-skill)
      :tools (tool-selection decl :skills)})))

(defn- norm-pack [id decl]
  (let [decl (or decl {})
        uri (u/expand (:uri decl))
        ;; a declared :type wins: a `file://` URI is usually a directory to
        ;; link straight into, but it can also be a git repo worth cloning
        type (cond (:type decl) (keyword (u/kw->str (:type decl)))
                   (nil? uri) :none
                   (str/starts-with? uri "file://") :file
                   (str/starts-with? uri "/") :file
                   :else :git)]
    (u/prune-nils
     {:id id
      :kind :skill-packs
      :uri uri
      :type type
      :ref (:ref decl)
      :dir (or (:dir decl) "skills")
      :root (case type
              :file (u/abs-path (str/replace uri #"^file://" ""))
              :git (str u/home "/.agents/skill-packs/" (name id))
              nil)})))

(defn- norm-provider [id decl]
  (let [decl (or decl {})]
    (u/prune-nils
     {:id id
      :kind :providers
      :url (:url decl)
      :key (refs/parse (:key decl))
      :key-name (:key-name decl)
      :api (or (:api decl) "openai-completions")
      :models (let [m (:models decl)]
                (cond (nil? m) :all
                      (= :all m) :all
                      :else (mapv str m)))
      :overrides (:overrides decl)
      :per-tool (norm-per-tool id decl norm-provider)
      :tools (tool-selection decl :providers)})))

(defn- norm-memory [id decl]
  (let [decl (or decl {})
        from (or (:from decl) (:path decl))]
    (u/prune-nils
     {:id id
      :kind :memory
      :from (some-> from u/expand (str/replace #"^file://" "") u/abs-path)
      :mode (keyword (u/kw->str (or (:mode decl) :symlink)))
      :scope (keyword (u/kw->str (or (:scope decl) :global)))
      :project (:project decl)
      :tools (tool-selection decl :memory)})))

(defn- norm-settings
  "Per-tool settings map: values kept as-is, keys normalized to kebab keywords.

   `:on #{:a :b}` / `:off #{:c}` are the terse spelling of the boolean flags —
   an explicit `:a true` in the same map still wins."
  [decl]
  (let [decl (or decl {})
        flags (merge (zipmap (map keyword (as-set (:on decl))) (repeat true))
                     (zipmap (map keyword (as-set (:off decl))) (repeat false)))]
    (into flags
          (map (fn [[k v]] [(keyword (str/replace (u/kw->str k) "_" "-")) v]))
          (dissoc decl :on :off))))

(defn- project-executors
  "A project either overrides settings per executor (`{:claude {...}}`) or just
   names the executors it applies to (`#{:claude}`) — the second is the common
   case and reads better than a map of empty maps."
  [decl]
  (let [x (or (:executors decl) (:cli-code decl))]
    (cond
      (map? x) (into {} (map (fn [[t s]] [t (norm-settings s)])) x)
      (coll? x) (into {} (map (fn [t] [(keyword t) {}])) x)
      (keyword? x) {x {}}
      :else nil)))

(defn- norm-project [id decl]
  (let [decl (or decl {})
        executors (project-executors decl)]
    (u/prune-nils
     {:id id
      :kind :projects
      :path (project-path id decl)
      :trusted (:trusted decl)
      :for-tools (if (or (:tools decl) (:for decl))
                   (tool-selection decl :projects)
                   ;; naming executors is also how a project says who it is for
                   (or (some->> (keys executors)
                                (filter #(supports? % :projects))
                                not-empty
                                set)
                       (tool-selection decl :projects)))
      :tools executors
      :mcp (as-set (:mcp decl))
      :skills (as-set (:skills decl))
      :permissions (norm-permissions (:permissions decl))
      :agents (vec (:agents decl))
      :memory (:memory decl)})))

(defn- scope-mcps
  "An MCP named by a project belongs to that project, not to the whole machine.

   Declaring it under `:mcps` is how it gets a definition; listing it in a
   project's `:mcp` is what says where it lives. `:scope :global` on the
   declaration overrides that for a server genuinely wanted everywhere.

   Project-named servers default to `:local` — Claude Code's local scope, the
   project's own entry in `~/.claude.json`. `:scope :project` is the opt-in
   for a server meant to be shared: it lands in the project's `.mcp.json`,
   which is a tracked file and no place for a token."
  [mcps projects]
  (let [used-by (reduce (fn [acc [pid proj]]
                          (reduce (fn [a mid] (update a mid (fnil conj #{}) pid))
                                  acc (:mcp proj)))
                        {} projects)]
    (into {} (map (fn [[id m]]
                    (let [ps (get used-by id)]
                      [id (assoc m
                                 :scope (or (:scope m) (if (seq ps) :local :global))
                                 :used-by (or ps #{}))])))
          mcps)))

(defn active-tools
  "The tools this config puts to work: the executors its projects name.

   A project that names no executors has said nothing about tools, and a config
   with no projects at all is still a config — neither narrows anything. But
   once a project does name its executors, the tools left unnamed are ones
   nothing asked for, and agentctl has no business writing their files."
  [cfg]
  (or (not-empty (reduce (fn [acc [_ proj]] (into acc (keys (:tools proj))))
                         #{} (:projects cfg)))
      (set all-tools)))

(defn- scope-skills
  "A skill a project asks for belongs to that project. Declaring it under
   `:skills` is how it gets a source; naming it in a project's `:skills` is
   what says who gets it and where it lands — the same split `:mcps` and a
   project's `:mcp` already have. `:scope :global` on the declaration keeps a
   skill user-wide even when a project names it.

   Only an explicit `:executors` on the project narrows the tools: without one
   the project has said nothing about them. An explicit `:tools`/`:for` on the
   skill wins, the way it does on an MCP."
  [skills raw-skills projects]
  (let [named-by (reduce (fn [acc [pid proj]]
                           (reduce (fn [a sid] (update a sid (fnil conj #{}) pid))
                                   acc (:skills proj)))
                         {} projects)
        used-by (reduce (fn [acc [_ proj]]
                          (if-let [ex (not-empty (set (keys (:tools proj))))]
                            (reduce (fn [a sid] (update a sid (fnil into #{}) ex))
                                    acc (:skills proj))
                            acc))
                        {} projects)]
    (into {}
          (map (fn [[id s]]
                 (let [ex (get used-by id)
                       ps (get named-by id)
                       decl (get raw-skills id)]
                   [id (cond-> (assoc s
                                      :scope (or (:scope s) (if (seq ps) :project :global))
                                      :used-by (or ps #{}))
                         (and ex (not (or (:tools decl) (:for decl))))
                         (assoc :tools (set (filter #(supports? % :skills) ex))))])))
          skills)))

(defn normalize [raw path & [load-findings]]
  (let [projects (into {} (map (fn [[k v]] [k (norm-project k v)])) (:projects raw))]
    {:source path
     :raw raw
     :load-findings (vec load-findings)
     :tools (into {} (map (fn [[t s]] [t (norm-settings s)]))
                  (or (:executors raw) (:cli-code raw)))
     :mcps (scope-mcps (into {} (map (fn [[k v]] [k (norm-mcp k v)])) (:mcps raw))
                       projects)
     :skills (scope-skills (into {} (map (fn [[k v]] [k (norm-skill k v)])) (:skills raw))
                           (:skills raw)
                           projects)
     :skill-packs (into {} (map (fn [[k v]] [k (norm-pack k v)])) (:skill-packs raw))
     :providers (into {} (map (fn [[k v]] [k (norm-provider k v)]))
                      (merge (:extra-providers raw) (:providers raw)))
     :memory (into {} (map (fn [[k v]] [k (norm-memory k v)])) (:memory raw))
     :projects projects}))

;; ---------------------------------------------------------------- structural check

(def known-top-keys
  #{:executors :cli-code :projects :mcps :skills :skill-packs :extra-providers :providers :memory :defaults
    :#let})

(defn structural-findings [cfg]
  (let [raw (:raw cfg)]
    (concat
     (:load-findings cfg)
     ;; :cli-code was the original spelling; still read, so an old file keeps
     ;; working, but it is not what import writes any more
     (when (contains? raw :cli-code)
       [(finding :warn [:cli-code] ":cli-code is the old name for :executors — rename it")])
     (for [[_ proj] (:projects raw) :when (contains? proj :cli-code)]
       (finding :warn [:projects :cli-code] ":cli-code is the old name for :executors — rename it"))
     (for [k (keys raw) :when (not (known-top-keys k))]
       (finding :warn [k] (str "unknown top-level key " k " — ignored")))
     (for [t (keys (:tools cfg)) :when (not (some #{t} all-tools))]
       (finding :error [:executors t] (str "unknown tool " t)))
     ;; a project naming its executors is what puts a tool to work; settings for
     ;; a tool no project names are read, planned for nothing, and easy to
     ;; mistake for applied config
     (let [active (active-tools cfg)]
       (for [t (keys (:tools cfg)) :when (and (some #{t} all-tools) (not (contains? active t)))]
         (finding :warn [:executors t]
                  (str "no project names " t " in its :executors — its settings are not applied"))))
     (for [[id s] (:skills cfg)
           :when (and (:from s) (not (contains? (:skill-packs cfg) (:from s))))]
       (finding :error [:skills id] (str "skill references undefined pack " (:from s))))
     (for [[id s] (:skills cfg) :when (and (nil? (:from s)) (nil? (:path s)))]
       (finding :error [:skills id] "skill needs :from <pack> or :path <dir>"))
     (for [[id m] (:mcps cfg)
           :when (and (= :stdio (:transport m)) (nil? (:command m)))]
       (finding :error [:mcps id]
                "stdio MCP server needs :command (or :url for http) — an empty map declares nothing"))
     (for [[id p] (:providers cfg) :when (nil? (:url p))]
       (finding :error [:providers id] "provider needs :url"))
     (for [[pid proj] (:projects cfg)
           mid (:mcp proj)
           :when (not (contains? (:mcps cfg) mid))]
       (finding :error [:projects pid :mcp] (str "references undefined mcp " mid)))
     (for [[pid proj] (:projects cfg)
           sid (:skills proj)
           :when (not (or (contains? (:skills cfg) sid)
                          (contains? (:skill-packs cfg) sid)))]
       (finding :error [:projects pid :skills]
                (str "references undefined skill or pack " sid)))
     (for [[id m] (:memory cfg) :when (not (u/exists? (:from m)))]
       (finding :error [:memory id] (str "source not found: " (u/tilde (:from m))))))))

;; ---------------------------------------------------------------- entry

(defn load-config
  ([] (load-config default-path))
  ([path]
   (let [path (u/abs-path path)]
     (when-not (u/exists? path)
       (throw (ex-info (str "config not found: " (u/tilde path)) {:path path})))
     (let [raw (try (edn/read-string (slurp path))
                    (catch Exception e
                      (throw (ex-info (str "cannot parse " (u/tilde path) ": " (.getMessage e))
                                      {:path path}))))]
       (when-not (map? raw)
         (throw (ex-info "agents.edn must contain a map" {:path path})))
       (let [{:keys [raw findings]} (expand-lets raw)]
         (normalize raw path findings))))))
