(ns agentctl.config
  "Reader + normalizer for ~/.config/agents.edn.

   The declared file is convenience-shaped (loose, terse). Everything downstream
   consumes the normalized shape produced here, so adapters never re-interpret
   surface syntax."
  (:require [agentctl.refs :as refs]
            [agentctl.scope :as scope]
            [agentctl.sources :as sources]
            [agentctl.util :as u]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(def all-tools [:claude :codex :pi :omp :llm :antigravity])

(def capabilities
  "Which resource kinds each tool can be provisioned with."
  {:claude      #{:settings :mcps :skills :memory :projects :permissions :hooks}
   :codex       #{:settings :mcps :skills :memory :projects :providers}
   :pi          #{:settings :mcps :skills :memory :providers :projects :extensions}
   :omp         #{:settings :mcps :skills :memory :providers}
   :llm         #{:providers :settings}
   :antigravity #{:settings :mcps :skills :memory :projects}})

(def cli-names
  "The binary each tool is invoked as, where it differs from the tool key."
  {:antigravity "agy"})

(defn cli-name [tool] (get cli-names tool (name tool)))

(def agent-names
  "How the plan names each tool: the `skills` CLI's agent ids where it has one."
  {:claude "claude-code" :codex "codex" :pi "pi" :omp "omp" :llm "llm" :antigravity "antigravity"})

(defn agent-name [tool] (get agent-names tool (name tool)))

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

;; ---------------------------------------------------------------- :#def

(def def-key :#def)

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

(defn expand-defs
  "Resolve root-level `:#def` bindings into the `$name` references that use them.

   Root level only: `:#def` nested inside a project or an mcp is a config error,
   not a local scope. An unbound `$NAME` is left untouched — that spelling is
   the environment-variable reference, and shadowing it here would be surprising."
  [raw]
  (let [bindings (into {} (map (fn [[k v]] [(u/kw->str k) v])) (get raw def-key))
        body (dissoc raw def-key)
        nested (volatile! false)
        _ (walk/postwalk (fn [x] (when (and (map? x) (contains? x def-key)) (vreset! nested true)) x) body)
        self (for [[k v] bindings
                   n (distinct (keep let-name (tree-seq coll? seq v)))
                   :when (contains? bindings n)]
               (finding :error [def-key (keyword k)]
                        (str "$" n " — a :#def value cannot reference another binding")))
        errors (concat (when @nested
                         [(finding :error [def-key]
                                   ":#def is only valid at the top level of agents.edn")])
                       self)
        ;; a lowercase $name that resolves to nothing is far more likely a typo
        ;; than a deliberate env var, which is conventionally SHOUTED
        unbound (when (empty? errors)
                  (for [n (distinct (keep let-name (tree-seq coll? seq body)))
                        :when (and (not (contains? bindings n)) (not= n (str/upper-case n)))]
                    (finding :warn [def-key]
                             (str "$" n " is not a :#def binding — resolved as env var $" n))))]
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

(defn- norm-headers [m]
  (not-empty (into {} (map (fn [[k v]] [k (refs/parse v)])) m)))

(defn- norm-mcp [id decl]
  (let [decl (mcp-decl decl)
        env (into {} (map (fn [[k v]] [k (refs/parse v)])) (:env decl))
        headers (norm-headers (:headers decl))
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
      :headers headers
      :bearer-token-env (:bearer-token-env decl)
      :cwd (some-> (:cwd decl) u/abs-path)
      :enabled (get decl :enabled true)
      :scope (or (case (:at decl) :repo :project :machine :local nil)
                 (some-> (:scope decl) u/kw->str keyword))
      :scope-by-tool (:scope-by-tool decl)
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

(defn norm-pack [id decl]
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
      ;; fetched only because a `[:gh …]` skill key names the repository
      :implicit (:implicit decl)
      :alias (some-> (:alias decl) name)
      :dir (or (:dir decl) "skills")
      :root (case type
              :file (u/abs-path (str/replace uri #"^file://" ""))
              :git (str u/home "/.agents/skill-packs/" (name id))
              nil)})))

(def model-caps
  "What a model can do, declared rather than detected: a custom id or alias
   is nothing a tool can look up, so without these it falls back to text-only,
   no thinking and generic limits. DSL key -> pi's field for it, which is also
   accepted as a spelling. Nested maps (`:cost`, `:thinking-level-map`,
   `:compat`) are pi's own and pass through as written."
  {:reasoning :reasoning
   :input :input
   :context-window :contextWindow
   :max-tokens :maxTokens
   :cost :cost
   :thinking-level-map :thinkingLevelMap
   :compat :compat})

(def model-caps-tools
  "Tools that write `model-caps`; every other provider tool says it does not."
  #{:pi})

(def ^:private known-model-keys
  (into #{:id :name :api :url :headers} cat model-caps))

(defn- model-caps-of
  "Declared capabilities. Presence is what counts: `false` is a declaration
   (and so is nil inside a nested map — pi reads a nil thinking level as
   unsupported); only a top-level nil means unset."
  [m]
  (not-empty
   (into {} (keep (fn [[dsl native]]
                    (let [v (if (contains? m dsl) (get m dsl) (get m native))]
                      (when (some? v)
                        [dsl (if (and (= :input dsl) (sequential? v)) (mapv u/kw->str v) v)]))))
         model-caps)))

(defn- norm-provider-model
  "A model is a bare id, or a map carrying what differs about it. The map form
   is what lets one provider entry serve two pools of the same host: pi, omp
   and llm all take headers per model, so a routing header can ride on the
   models it routes instead of forcing a second provider row whose only
   difference is one header value."
  [m]
  (let [m (if (map? m) m {:id m})]
    (u/prune-nils
     {:id (str (:id m))
      :name (:name m)
      :api (:api m)
      :url (:url m)
      :headers (norm-headers (:headers m))
      :caps (model-caps-of m)
      ;; kept so validation can name a key it does not know: dropping one the
      ;; user wrote, without a word, is how a model ends up quietly text-only
      :decl-keys (set (keys m))})))

(defn- norm-provider [id decl]
  (let [decl (or decl {})]
    (u/prune-nils
     {:id id
      :kind :providers
      :url (:url decl)
      :key (refs/parse (:key decl))
      :key-name (:key-name decl)
      ;; a scalar :api is one dialect, as before. A vector is a menu: the
      ;; provider serves all of them and each tool takes the one it speaks.
      :api (let [a (:api decl)]
             (cond (nil? a) "openai-completions"
                   (sequential? a) (mapv u/kw->str a)
                   :else (u/kw->str a)))
      :headers (norm-headers (:headers decl))
      :models (let [m (:models decl)]
                (cond (nil? m) :all
                      (= :all m) :all
                      :else (mapv norm-provider-model m)))
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

(defn- norm-hooks
  "`:hooks` is normally id-keyed, one hook's whole declaration — `:event`
   included — per id. Grouping by event instead reads better once several
   hooks share one: `{:SessionStart [{:id ... :command ...} ...]}`. Every
   entry there still needs its own `:id`, since that is what agentctl's
   ownership manifest tracks, not the event key it lives under; a missing
   one gets a positional placeholder so normalization can't collide two
   hooks onto the same key, and `:hook-id-missing` flags it for
   `structural-findings` to report as the error it is. Both forms — and a
   mix of the two in the same map — normalize to one flat {id decl}."
  [hooks]
  (into {}
        (mapcat (fn [[k v]]
                  (if (sequential? v)
                    (map-indexed
                     (fn [i decl]
                       [(or (:id decl) (keyword (str (u/kw->str k) "-" i)))
                        (cond-> (assoc (dissoc decl :id) :event k)
                          (nil? (:id decl)) (assoc :hook-id-missing true))])
                     v)
                    [[k v]])))
        hooks))

(defn- norm-settings
  "Per-tool settings map: values kept as-is, keys normalized to kebab keywords.

   `:on #{:a :b}` / `:off #{:c}` are the terse spelling of the boolean flags —
   an explicit `:a true` in the same map still wins."
  [decl]
  (let [decl (or decl {})
        flags (merge (zipmap (map keyword (as-set (:on decl))) (repeat true))
                     (zipmap (map keyword (as-set (:off decl))) (repeat false)))]
    (into flags
          (map (fn [[k v]]
                 (let [k' (keyword (str/replace (u/kw->str k) "_" "-"))]
                   [k' (case k' :hooks (norm-hooks v) :permissions (norm-permissions v) v)])))
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
      :trust-by-tool (:trust-by-tool decl)
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
  (into (or (not-empty (:active-tools cfg)) (not-empty (reduce (fn [acc [_ proj]] (into acc (keys (:tools proj))))
                               #{} (:projects cfg)))
            (set all-tools))
        ;; an extension names its CLI outright, with no project in between
        (mapcat :for-tools (vals (:extensions cfg)))))

(defn- bare-project-skills
  "A project can name a skill id that resolves only by scanning a declared
   pack's contents (`sources/locate-skill`), with no `:skills` entry at all.
   `scope-skills` derives `:tools` by walking every id a project names, so a
   bare id needs the same normalized shape as a declared one or it never
   picks up the executors that named it — this synthesizes that shape for
   every id that resolves to exactly one pack. Ambiguous, pending and unknown
   ids are left out; `sources/project-skills` and `structural-findings`
   resolve those themselves at plan/validate time, where the distinction
   between the three actually matters."
  [declared-skills skill-packs projects]
  (into {}
        (for [[_ proj] projects
              sid (:skills proj)
              :when (not (or (contains? declared-skills sid) (contains? skill-packs sid)))
              :let [{:keys [found]} (sources/locate-skill skill-packs sid)]
              :when (= 1 (count found))
              :let [[pid _] (first found)]]
          [sid {:id sid :kind :skills :from pid :mode :symlink}])))

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

(defn- normalize-legacy [raw path & [load-findings]]
  (let [projects (into {} (map (fn [[k v]] [k (norm-project k v)])) (:projects raw))
        skill-packs (into {} (map (fn [[k v]] [k (norm-pack k v)])) (:skill-packs raw))
        declared-skills (into {} (map (fn [[k v]] [k (norm-skill k v)])) (:skills raw))
        bare-skills (bare-project-skills declared-skills skill-packs projects)]
    {:source path
     :raw raw
     :load-findings (vec load-findings)
     :tools (into {} (map (fn [[t s]] [t (norm-settings s)]))
                  (or (:executors raw) (:cli-code raw)))
     :mcps (scope-mcps (into {} (map (fn [[k v]] [k (norm-mcp k v)])) (:mcps raw))
                       projects)
     ;; a bare id resolved via a pack scan gets the same :tools derivation as
     ;; a declared skill — see bare-project-skills
     :skills (scope-skills (merge bare-skills declared-skills) (:skills raw) projects)
     :skill-packs skill-packs
     :providers (into {} (map (fn [[k v]] [k (norm-provider k v)]))
                      (merge (:extra-providers raw) (:providers raw)))
     :memory (into {} (map (fn [[k v]] [k (norm-memory k v)])) (:memory raw))
     :projects projects}))

(defn- norm-extension
  "An extension is a checkout a CLI loads code from: a git repository cloned
   under ~/.agents/extensions, or a directory used where it stands. `:acli`
   picks the CLIs — `:tools` is taken already, by the tool names the extension
   registers and the CLI should switch on by default."
  [k decl]
  (when-not (and (vector? k) (scope/source k))
    (throw (ex-info (str "invalid :extensions key " (pr-str k)
                         " — expected [:gh \"owner/repo\"] or [:uri \"…\"]") {:kind :extensions :key k})))
  (when-not (or (nil? decl) (map? decl))
    (throw (ex-info "keyed entry value must be a map" {:kind :extensions :key k})))
  (let [uri (scope/source k)
        id (if-let [a (:alias decl)] (keyword (name a)) (scope/basename uri))
        type (cond (:type decl) (keyword (u/kw->str (:type decl)))
                   (scope/local-uri? uri) :file
                   :else :git)
        sel (scope/acli (:acli decl))]
    (u/prune-nils
     {:id id
      :kind :extensions
      :uri uri
      :type type
      :ref (:ref decl)
      :build (:build decl)
      :default-tools (:tools decl)
      :in (:in decl)
      :acli sel
      :for-tools (set (if (or (nil? sel) (= :all sel))
                        (tools-for :extensions)
                        (filter #(supports? % :extensions) sel)))
      :root (case type
              :file (u/abs-path (str/replace uri #"^file://" ""))
              :git (str u/home "/.agents/extensions/" (name id))
              nil)})))

(defn- norm-extensions [raw]
  (when-not (or (nil? raw) (map? raw))
    (throw (ex-info ":extensions must be a map keyed by source, e.g. {[:gh \"owner/repo\"] {:acli [:pi]}}"
                    {:kind :extensions})))
  (into {} (map (fn [[k v]] (let [e (norm-extension k v)] [(:id e) e]))) raw))

(declare normalize-resources)

(def ^:private host-settings #{:ssh :os :home})
(def host-sections
  "What a host can declare so far; `validate` names anything else."
  #{:skills :skill-packs})

(defn- keyed-section? [v] (or (nil? v) (and (map? v) (every? vector? (keys v)))))

(defn- norm-host
  "A host's own declarations, in the keyed shape of the top-level sections. They
   sit inside the host rather than selecting from the top level because a source
   is a map key: a pack placed in a local project for one tool could not also be
   placed user-wide on a host for another. Nothing here reaches a local plan.

   A pack on a host defaults to `:in :all` — the top-level default of `:none`
   exists for packs that projects then name, and a host has no projects."
  [id decl source]
  (let [places-only-all? (fn [section allowed]
                           (every? #(contains? allowed (get (val %) :in :all)) (get decl section)))
        sections? (and (keyed-section? (:skills decl)) (keyed-section? (:skill-packs decl))
                       (places-only-all? :skills #{:all})
                       (places-only-all? :skill-packs #{:all :none}))
        body (when sections?
               (-> (select-keys decl host-sections)
                   (update :skill-packs #(into {} (map (fn [[k v]] [k (merge {:in :all} v)])) %))))]
    (u/prune-nils
     {:id id
      :ssh (:ssh decl)
      :os (:os decl)
      :home (:home decl)
      :sections-ok? sections?
      :unsupported (vec (sort (remove (into host-settings host-sections) (keys decl))))
      :cfg (normalize-resources (or body {}) source)})))

(defn normalize [raw path & [load-findings]]
  ;; read off the file as written: extensions are placed for a whole machine,
  ;; so they take no part in the entity lowering below
  (assoc (normalize-resources raw path load-findings)
         :extensions (norm-extensions (:extensions raw))
         :hosts (into {} (map (fn [[id decl]] [id (norm-host id decl path)])) (:hosts raw))))

(defn- normalize-resources [raw path & [load-findings]]
  (if-not (scope/dsl? raw)
    (normalize-legacy raw path load-findings)
    (let [{lowered :raw :keys [entities active-tools]} (scope/compile-config raw tools-for)
          cfg (normalize-legacy lowered path load-findings)
          cfg (assoc cfg :entity-dsl? true :entities entities :entity-raw raw :active-tools active-tools)
          ;; A globally placed pack installs its current contents. Re-normalizing
          ;; after fetch lets newly materialized skills join the same apply.
          pack-skills (into {} (for [e entities :when (and (= :skill-packs (:kind e)) (= :all (:in e)))
                                     dir (sources/skill-dirs (get-in cfg [:skill-packs (:id e)]))
                                     :let [s (sources/pack-skill (:id e) dir)]]
                                 [(:id s) (assoc s :scope :global :via-pack true
                                                 :tools (tool-selection (:decl e) :skills))]))]
      (-> cfg
          (update :skills #(merge pack-skills %))
          (update :skill-packs
                  #(reduce (fn [packs e]
                             (if (= :skill-packs (:kind e))
                               (assoc-in packs [(:id e) :tools] (tool-selection (:decl e) :skills)) packs)) % entities))))))

;; ---------------------------------------------------------------- structural check

(def ^:private thinking-levels #{"off" "minimal" "low" "medium" "high" "xhigh" "max"})
(def ^:private cost-keys #{"input" "output" "cacheRead" "cacheWrite"})

(defn- model-cap-problems
  "What is wrong with one model's declared capabilities, as messages. Shapes
   only — whether an upstream route really has that window or that price is
   the user's to know."
  [{:keys [caps decl-keys]}]
  (let [{:keys [reasoning input context-window max-tokens cost thinking-level-map compat]} caps]
    (concat
     (for [k (remove known-model-keys decl-keys)]
       [:warn (str "model field " k " is not one agentctl knows — not written (known: "
                   (str/join " " (sort (keys model-caps))) ")")])
     (for [[dsl native] model-caps
           :when (and (not= dsl native) (decl-keys dsl) (decl-keys native))]
       [:error (str dsl " and " native " are the same field — keep one")])
     (when (and (contains? caps :reasoning) (not (boolean? reasoning)))
       [[:error ":reasoning must be true or false"]])
     (when (and (contains? caps :input)
                (not (and (sequential? input) (seq input) (every? #{"text" "image"} input))))
       [[:error ":input must be a non-empty vector of \"text\" and \"image\""]])
     (for [[k v] [[:context-window context-window] [:max-tokens max-tokens]]
           :when (and (some? v) (not (pos-int? v)))]
       [:error (str k " must be a positive integer")])
     (for [[k v] [[:cost cost] [:thinking-level-map thinking-level-map] [:compat compat]]
           :when (and (some? v) (not (map? v)))]
       [:error (str k " must be a map")])
     (when (map? cost)
       (let [ks (set (map u/kw->str (keys cost)))]
         (concat
          (for [k (sort (remove (conj cost-keys "tiers") ks))]
            [:error (str ":cost has no field " k)])
          (for [k (sort (remove ks cost-keys))]
            [:error (str ":cost needs " k " (input, output, cacheRead and cacheWrite are all required)")])
          (for [[k v] cost :when (and (cost-keys (u/kw->str k)) (not (number? v)))]
            [:error (str ":cost " (u/kw->str k) " must be a number")]))))
     (when (map? thinking-level-map)
       (for [[k v] thinking-level-map
             :let [msg (cond (not (thinking-levels (u/kw->str k)))
                             " is not a level (off minimal low medium high xhigh max)"
                             (not (or (nil? v) (string? v) (number? v)))
                             " must be a string, a number, or nil (unsupported)")]
             :when msg]
         [:error (str ":thinking-level-map " (u/kw->str k) msg)]))
     (when (map? compat)
       (for [[k v] compat
             :when (and (= "forceAdaptiveThinking" (u/kw->str k)) (not (boolean? v)))]
         [:error ":compat forceAdaptiveThinking must be true or false"])))))

(defn- provider-model-findings [cfg]
  (for [[id p] (:providers cfg)
        [where p] (cons [[:providers id] p]
                        (for [[t o] (:per-tool p)] [[:providers id :per-tool t] o]))
        m (when (vector? (:models p)) (:models p))
        [level msg] (model-cap-problems m)]
    (finding level (conj where :models (:id m)) msg)))

(def known-top-keys
  #{:executors :cli-code :projects :mcps :skills :skill-packs :extra-providers :providers :memory :defaults
    :settings :permissions :trust :hooks :extensions :hosts :#def})

(defn- host-findings
  "A host's `:ssh` goes on an ssh command line and its name into a file name, so
   neither is taken on trust."
  [cfg]
  (mapcat
   (fn [[id {:keys [ssh os home sections-ok? unsupported]}]]
     (let [where [:hosts id]]
       (concat
        (when-not (and (keyword? id) (re-matches #"[A-Za-z0-9][A-Za-z0-9_-]*" (name id)))
          [(finding :error where "host name must be letters, digits, - and _")])
        (when-not (and (string? ssh) (re-matches #"[^\s-][^\s]*" ssh))
          [(finding :error (conj where :ssh) ":ssh must be an ssh destination, such as \"user@host\"")])
        (when-not (#{:windows :linux :macos} os)
          [(finding :error (conj where :os) ":os must be :windows, :linux or :macos")])
        (when-not (or (nil? home) (string? home))
          [(finding :error (conj where :home) ":home must be a path on the host")])
        (when-not sections-ok?
          [(finding :error where
                    (str ":skills and :skill-packs on a host are keyed by source and placed :in :all"
                         " — a host has no projects"))])
        (for [k unsupported]
          (finding :warn (conj where k) (str (name k) " is not provisioned on hosts yet — ignored"))))))
   (:hosts cfg)))

(defn structural-findings [cfg]
  (let [raw (:raw cfg)]
    (concat
     (:load-findings cfg)
     (host-findings cfg)
     (for [{:keys [kind id decl]} (:entities cfg) :when (contains? decl :scope)]
       (finding :warn [kind id :scope] ":scope is deprecated; use :at :machine or :repo"))
     (for [{:keys [kind id in targets]} (:entities cfg)
           :when (and (vector? in) (empty? targets))]
       (finding :warn [kind id :in] (str (pr-str in) " matches no directory — placed nowhere")))
     (for [{:keys [kind id in]} (:entities cfg) :when (and (= kind :trust) (= in :all))]
       (finding :warn [kind id :in] "trust requires a repository path; tools have no user-wide trust location"))
     (for [{:keys [kind id decl targets]} (:entities cfg)
           :when (and (= kind :memory) (seq targets)
                      (not (u/exists? (some-> (or (:from decl) (:path decl)) u/expand (str/replace #"^file://" "")))))]
       (finding :error [kind id] "memory source not found"))
     (for [{:keys [kind id decl]} (:entities cfg)
           t (let [ts (:tools decl)] (cond (nil? ts) [] (= ts :all) [] (keyword? ts) [ts] :else ts))
           :when (not (some #{t} all-tools))]
       (finding :error [kind id :tools] (str "unknown tool " t)))
     ;; :cli-code was the original spelling; still read, so an old file keeps
     ;; working, but it is not what import writes any more
     (when (contains? raw :cli-code)
       [(finding :warn [:cli-code] ":cli-code is the old name for :executors — rename it")])
     (for [[_ proj] (:projects raw) :when (contains? proj :cli-code)]
       (finding :warn [:projects :cli-code] ":cli-code is the old name for :executors — rename it"))
     (mapcat
      (fn [[id e]]
        (concat
         (when-not (or (nil? (:default-tools e))
                       (and (vector? (:default-tools e)) (every? string? (:default-tools e))))
           [(finding :error [:extensions id :tools]
                     ":tools lists the tool names to switch on, as strings (e.g. [\"+name\"]) — :acli picks the CLIs")])
         (when-not (or (nil? (:build e)) (string? (:build e)))
           [(finding :error [:extensions id :build] ":build is a shell command, run in the extension's directory")])
         (when (and (some? (:in e)) (not= :all (:in e)))
           [(finding :warn [:extensions id :in] "extensions are installed user-wide; :in is ignored")])
         (for [t (let [a (:acli e)] (if (keyword? a) [] a)) :when (not (supports? t :extensions))]
           (finding (if (some #{t} all-tools) :warn :error) [:extensions id :acli]
                    (if (some #{t} all-tools)
                      (str (name t) " has no extensions agentctl can install — ignored")
                      (str "unknown tool " t))))))
      (:extensions cfg))
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
     (provider-model-findings cfg)
     (for [[pid proj] (:projects cfg)
           mid (:mcp proj)
           :when (not (contains? (:mcps cfg) mid))]
       (finding :error [:projects pid :mcp] (str "references undefined mcp " mid)))
     ;; a bare id can also resolve to a skill directory found inside a
     ;; declared pack (`sources/locate-skill`) — no `:skills` entry required
     ;; for that, the common case this DSL is built to avoid
     (for [[pid proj] (:projects cfg)
           sid (:skills proj)
           :when (not (or (contains? (:skills cfg) sid) (contains? (:skill-packs cfg) sid)))
           :let [{:keys [found not-ready]} (sources/locate-skill (:skill-packs cfg) sid)]
           :when (and (empty? found) (empty? not-ready))]
       (finding :error [:projects pid :skills] (str "references undefined skill or pack " sid)))
     (for [[pid proj] (:projects cfg)
           sid (:skills proj)
           :when (not (or (contains? (:skills cfg) sid) (contains? (:skill-packs cfg) sid)))
           :let [{:keys [found]} (sources/locate-skill (:skill-packs cfg) sid)]
           :when (> (count found) 1)]
       (finding :error [:projects pid :skills]
                (str sid " exists in more than one pack (" (str/join ", " (map (comp name first) found))
                     ") — declare :skills {" (name sid) " {:from <pack>}} to disambiguate")))
     (for [[id m] (:memory cfg) :when (not (u/exists? (:from m)))]
       (finding :error [:memory id] (str "source not found: " (u/tilde (:from m)))))
     (for [[id h] (get-in cfg [:tools :claude :hooks]) :when (nil? (:event h))]
       (finding :error [:executors :claude :hooks id] "hook needs :event"))
     (for [[id h] (get-in cfg [:tools :claude :hooks]) :when (nil? (:command h))]
       (finding :error [:executors :claude :hooks id] "hook needs :command"))
     (for [[pid p] (:projects cfg) [id h] (get-in p [:tools :claude :hooks])
           :when (or (nil? (:event h)) (nil? (:command h)) (:hook-id-missing h))]
       (finding :error [:hooks pid id] "project hook needs an id, event, and command"))
     (for [[_ h] (get-in cfg [:tools :claude :hooks]) :when (:hook-id-missing h)]
       (finding :error [:executors :claude :hooks (:event h)] "hook grouped under :hooks needs :id")))))

;; ---------------------------------------------------------------- entry

(defn parse-config
  "Normalize config *text*, which need not be what `path` currently holds — the
   GUI plans against an unsaved buffer. `path` names the source in messages and
   is what every relative path in the result is resolved against."
  [text path]
  (let [path (u/abs-path path)
        raw (try (edn/read-string text)
                 (catch Exception e
                   (throw (ex-info (str "cannot parse " (u/tilde path) ": " (.getMessage e))
                                   {:path path}))))]
    (when-not (map? raw)
      (throw (ex-info "agents.edn must contain a map" {:path path})))
    (let [{:keys [raw findings]} (expand-defs raw)]
      (normalize raw path findings))))

(defn load-config
  ([] (load-config default-path))
  ([path]
   (let [path (u/abs-path path)]
     (when-not (u/exists? path)
       (throw (ex-info (str "config not found: " (u/tilde path)) {:path path})))
     (parse-config (slurp path) path))))
