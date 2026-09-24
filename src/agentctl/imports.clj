(ns agentctl.imports
  "Reverse direction: read the live environment and emit agents.edn.

   Secrets are never emitted literally — every credential-shaped value becomes
   a !bw:// placeholder and is reported in the notes so the vault item can be
   created before the next apply."
  (:require [agentctl.adapters.antigravity :as antigravity]
            [agentctl.adapters.claude :as claude]
            [agentctl.adapters.codex :as codex]
            [agentctl.adapters.llm :as llm]
            [agentctl.adapters.omp :as omp]
            [agentctl.adapters.pi :as pi]
            [agentctl.refs :as refs]
            [agentctl.scope :as scope]
            [agentctl.toml :as toml]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [clojure.pprint]
            [clojure.string :as str]))

(def notes (atom []))
(defn- note! [s] (swap! notes conj s) nil)

(defn- redact-map
  "Replace secret-shaped values with bw placeholders."
  [scope m]
  (into {}
        (map (fn [[k v]]
               [k (if (refs/secret-shaped? k v)
                    (do (note! (str "redacted " scope "." (u/kw->str k)
                                    " -> create bitwarden item `" scope "` field `" (u/kw->str k) "`"))
                        (refs/redact scope k))
                    v)]))
        m))

(defn- invert [key-map] (into {} (map (fn [[k v]] [(u/kw->str v) k])) key-map))

;; ---------------------------------------------------------------- settings

(defn scan-settings []
  (u/prune-nils
   {:claude (let [s (u/read-json claude/settings-file)
                  inv (invert claude/setting-keys)]
              (not-empty (into {} (keep (fn [[k v]] (when-let [dk (get inv (name k))]
                                                      (when-not (map? v) [dk v]))))
                               s)))
    :codex (let [t (try (toml/read-toml codex/config-file) (catch Exception _ nil))
                 inv (invert codex/setting-keys)]
             (not-empty
              (cond-> (into {} (keep (fn [[k v]] (when-let [dk (get inv k)]
                                                  (when (string? v) [dk v]))))
                            t)
                (seq (get-in t ["tui" "keymap"]))
                (assoc :keymap (get-in t ["tui" "keymap"])))))
    :pi (let [s (u/read-json pi/settings-file)
              inv (invert pi/setting-keys)]
          (not-empty (into {} (keep (fn [[k v]] (when-let [dk (get inv (name k))]
                                                  (when (string? v) [dk v]))))
                           s)))
    :omp (let [c (u/read-yaml omp/config-file)]
           (not-empty
            (u/prune-nils
             {:model (get-in c [:modelRoles :default])
              :model-roles (not-empty (into {} (get c :modelRoles)))
              :personality (:personality c)
              :thinking (:defaultThinkingLevel c)})))
    :antigravity (let [s (u/read-json antigravity/settings-file)
                       inv (invert antigravity/setting-keys)]
                   (not-empty (into {} (keep (fn [[k v]] (when-let [dk (get inv (name k))]
                                                           (when (or (string? v) (boolean? v)) [dk v]))))
                                    s)))
    :llm (let [d (some-> (u/slurp-safe (llm/default-model-file)) str/trim)
               aliases (u/read-json (llm/aliases-file))]
           (not-empty (u/prune-nils {:model d :aliases (not-empty aliases)})))}))

;; ---------------------------------------------------------------- shared

(defn- most-common
  "The definition shared by the most tools — the sensible base for overrides."
  [defs]
  (->> defs (map #(dissoc % :tool)) frequencies (sort-by val >) ffirst))

(defn- with-overrides
  "Collapse per-tool definitions into one entry plus :per-tool for the outliers."
  [id defs]
  (let [base (most-common defs)
        overrides (into {}
                        (keep (fn [d]
                                (let [diff (into {} (remove (fn [[k v]] (= v (get base k))))
                                                 (dissoc d :tool))]
                                  (when (seq diff) [(:tool d) diff]))))
                        defs)]
    (when (seq overrides)
      (note! (str id ": definitions differ across "
                  (str/join ", " (map (comp name key) overrides))
                  " — kept as :per-tool overrides")))
    (u/prune-nils
     (assoc base
            :tools (vec (sort (distinct (map :tool defs))))
            :per-tool (not-empty overrides)))))

;; ---------------------------------------------------------------- mcps

(def ^:private modelled-mcp-keys
  #{:command :args :url :env :headers :enabled :type :transport :name :disabled_reason
    :auth_status :startup_timeout_sec :tool_timeout_sec :cwd
    ;; antigravity's spelling of :url / :enabled
    :serverUrl :disabled})

(defn- normalize-entry [tool nm entry]
  (let [t (or (:transport entry) entry)
        extra (not-empty (into {} (remove (fn [[k _]] (contains? modelled-mcp-keys k))) entry))]
    (u/prune-nils
     {:tool tool
      :command (or (:command t) (:command entry))
      :args (not-empty (vec (or (:args t) (:args entry))))
      :url (or (:url t) (:url entry))
      :env (not-empty (redact-map nm (or (:env t) (:env entry))))
      ;; an http server's whole credential rides in a header, so it needs the
      ;; same redaction as :env — left unmodelled it would land in :extra verbatim
      :headers (not-empty (redact-map nm (or (:headers t) (:headers entry))))
      :enabled (if (false? (:enabled entry)) false nil)
      :cwd (or (:cwd t) (:cwd entry))
      ;; keys agentctl does not model are round-tripped verbatim
      :extra extra})))

(defn scan-mcps []
  (let [named (concat
               (for [[k v] (claude/current-mcps)] [(name k) (normalize-entry :claude (name k) v)])
               (for [[nm v] (codex/mcp-list)] [(name nm) (normalize-entry :codex (name nm) v)])
               (for [[k v] (:mcpServers (u/read-json pi/mcp-file))] [(name k) (normalize-entry :pi (name k) v)])
               (for [[k v] (:mcpServers (u/read-json omp/mcp-file))] [(name k) (normalize-entry :omp (name k) v)])
               ;; antigravity spells the endpoint `serverUrl` and inverts the
               ;; flag; translated here so one server declared for several tools
               ;; still collapses into a single entry instead of a :per-tool split
               (for [[k v] (:mcpServers (u/read-json antigravity/mcp-file))]
                 [(name k) (normalize-entry :antigravity (name k)
                                            (cond-> (dissoc v :serverUrl :disabled)
                                              (:serverUrl v) (assoc :url (:serverUrl v))
                                              (true? (:disabled v)) (assoc :enabled false)))]))]
    (into (sorted-map)
          (for [[nm entries] (group-by first named)]
            [(keyword nm) (with-overrides nm (map second entries))]))))

;; ---------------------------------------------------------------- providers

(defn- provider-id [base-url existing-name]
  (or (some-> existing-name keyword)
      (-> (str/replace (str base-url) #"^https?://" "")
          (str/replace #"[:/].*$" "")
          (str/replace #"\." "-")
          keyword)))

(defn- derived-id? [id] (boolean (re-find #"\d" (name id))))

(defn scan-providers []
  (let [codex-cfg (try (toml/read-toml codex/config-file) (catch Exception _ nil))
        entries (concat
                 (for [[nm p] (get codex-cfg "model_providers")]
                   {:id (keyword nm) :tool :codex
                    :url (get p "base_url")
                    :key (when-let [e (get p "env_key")] (symbol (str "$" e)))
                    :api (if (= "responses" (get p "wire_api")) "responses" "openai-completions")})
                 (for [[nm p] (:providers (u/read-json pi/models-file))]
                   {:id (keyword (name nm)) :tool :pi
                    :url (:baseUrl p)
                    :key (when (:apiKey p)
                           (do (note! (str "pi provider " (name nm)
                                           " has a plaintext apiKey — emitted as !bw:// placeholder"))
                               (refs/redact (str "provider-" (name nm)) :apiKey)))
                    :api (:api p)})
                 (for [[nm p] (:providers (u/read-yaml omp/models-file))]
                   {:id (keyword (name nm)) :tool :omp
                    :url (:baseUrl p)
                    :key (when (:apiKey p)
                           (do (note! (str "omp provider " (name nm)
                                           " has a plaintext apiKey — emitted as !bw:// placeholder"))
                               (refs/redact (str "provider-" (name nm)) :apiKey)))
                    :api (:api p)})
                 (for [[base es] (group-by :api_base (or (u/read-yaml (llm/models-file)) []))
                       :when base]
                   {:id (provider-id base nil) :tool :llm
                    :url base
                    :key-name (:api_key_name (first es))
                    :models (vec (sort (map :model_id es)))}))
        ;; llm entries are keyed by host, so fold them into the named provider
        ;; that serves the same endpoint instead of inventing a second provider
        by-url (into {} (for [e entries
                              :when (not (derived-id? (:id e)))]
                          [(str/replace (str (:url e)) #"/+$" "") (:id e)]))
        entries (map (fn [e]
                       (if (derived-id? (:id e))
                         (assoc e :id (get by-url (str/replace (str (:url e)) #"/+$" "") (:id e)))
                         e))
                     entries)]
    (into (sorted-map)
          (for [[id defs] (group-by :id entries)]
            [id (with-overrides (name id) (map #(dissoc % :id) defs))]))))

;; ---------------------------------------------------------------- skills

(def skill-dirs
  {:claude claude/skills-dir
   :codex codex/skills-dir
   :pi pi/skills-dir
   :omp omp/skills-dir
   :antigravity antigravity/skills-dir})

(defn- real-path [p]
  (try (str (fs/real-path p)) (catch Exception _ (u/abs-path p))))

(defn scan-skills []
  (let [found (for [[tool dir] skill-dirs
                    :when (u/exists? dir)
                    entry (fs/list-dir dir)
                    :when (u/exists? (str entry "/SKILL.md"))]
                {:tool tool :name (fs/file-name entry) :source (real-path entry)})
        ;; a skill sitting in a tool's own directory is not a pack, it is in place
        pack-roots (into {} (for [dir (distinct (map (comp str fs/parent :source) found))
                                  :let [nm (if (= "skills" (fs/file-name dir))
                                             (str (fs/file-name (fs/parent dir)))
                                             (str (fs/file-name dir)))]
                                  :when (not (str/starts-with? nm "."))]
                              [dir (keyword nm)]))
        skills (into (sorted-map)
                     (for [[nm entries] (group-by :name found)
                           :let [defs (for [e entries]
                                        (let [parent (str (fs/parent (:source e)))
                                              pack (get pack-roots parent)]
                                          (u/prune-nils
                                           {:tool (:tool e)
                                            :from pack
                                            :path (when-not pack (u/tilde (:source e)))
                                            ;; a differing real path per tool is kept per-tool
                                            :source-path (u/tilde (:source e))})))
                                 base (most-common (map #(dissoc % :source-path) defs))
                                 per-tool (into {}
                                                (keep (fn [d]
                                                        (when (not= (:source-path d)
                                                                    (:source-path (first defs)))
                                                          [(:tool d) {:path (:source-path d)}])))
                                                defs)]]
                       (do (when (seq per-tool)
                             (note! (str nm ": installed from different directories per tool"
                                         " — kept as :per-tool paths")))
                           [(keyword nm)
                            (u/prune-nils
                             (assoc base
                                    :tools (vec (sort (distinct (map :tool entries))))
                                    :per-tool (not-empty per-tool)))])))]
    {:skills skills
     :packs (into (sorted-map)
                  (for [[dir id] pack-roots
                        :when (some (fn [[_ s]] (= id (:from s))) skills)
                        :let [skills-sub? (= "skills" (fs/file-name dir))]]
                    [id (u/prune-nils
                         {:uri (str "file://" (u/tilde (if skills-sub? (str (fs/parent dir)) dir)))
                          :dir (when skills-sub? "skills")})]))}))

;; ---------------------------------------------------------------- memory

(def memory-files
  {:claude claude/memory-file
   :codex codex/memory-file
   :pi pi/memory-file
   :omp omp/memory-file
   :antigravity antigravity/memory-file})

(defn scan-memory []
  (let [entries (for [[tool path] memory-files
                      :when (u/exists? path)]
                  {:tool tool
                   :source (real-path path)
                   :link (fs/sym-link? path)})
        by-src (group-by :source entries)]
    (into (sorted-map)
          (for [[src es] by-src
                :let [nm (str/lower-case (str/replace (str (fs/file-name src)) #"\.md$" ""))
                      id (keyword (if (str/blank? nm) "memory" nm))]]
            [id {:from (u/tilde src)
                 :mode (if (every? :link es) :symlink :copy)
                 :tools (vec (sort (map :tool es)))}]))))

;; ---------------------------------------------------------------- projects

(defn scan-projects []
  (let [codex-cfg (try (toml/read-toml codex/config-file) (catch Exception _ nil))
        codex-projects (for [[path v] (get codex-cfg "projects")]
                         [path (= "trusted" (get v "trust_level")) :codex])
        pi-trust (for [[k v] (u/read-json pi/trust-file)] [(u/key-str k) (boolean v) :pi])
        claude-projects (for [[k v] (:projects (u/read-json claude/runtime-file))
                              :when (:hasTrustDialogAccepted v)]
                          [(u/key-str k) true :claude])
        agy-trust (for [p (:trustedWorkspaces (u/read-json antigravity/settings-file))]
                    [(str p) true :antigravity])
        all (group-by first (concat codex-projects pi-trust claude-projects agy-trust))
        interesting (for [[path entries] all
                          ;; "/" and $HOME are trust artefacts, not projects
                          :when (and (some second entries)
                                     (not (contains? #{"/" u/home ""} path)))]
                      path)
        ;; two checkouts can share a basename; disambiguate with the parent dir
        by-name (group-by #(fs/file-name %) interesting)]
    (into (sorted-map)
          (for [[nm paths] by-name
                path paths
                :let [base (if (str/blank? (str nm)) "root" (str nm))
                      id (keyword (if (> (count paths) 1)
                                    (str (fs/file-name (fs/parent path)) "-" base)
                                    base))]]
            [id {:path (u/tilde path)
                 :trusted true
                 ;; only the tools that trust it today, so import -> apply is a no-op
                 :tools (vec (sort (distinct (keep (fn [[_ trusted tool]] (when trusted tool))
                                                   (get all path)))))}]))))

;; ---------------------------------------------------------------- assembly

(declare entity-config)

(defn scan [{:keys [existing]}]
  (reset! notes [])
  (let [{:keys [skills packs]} (scan-skills)
        discovered (u/prune-nils
                    {:executors (scan-settings)
                     :mcps (not-empty (scan-mcps))
                     :skills (not-empty skills)
                     :skill-packs (not-empty packs)
                     :extra-providers (not-empty (scan-providers))
                     :memory (not-empty (scan-memory))
                     :projects (not-empty (scan-projects))})]
    {:config (if (scope/dsl? existing)
               (merge-with (fn [a b]
                             (if (and (vector? a) (vector? b))
                               (let [ids (set (keep :id b))]
                                 (vec (concat (remove #(ids (:id %)) a) b))) b))
                           (entity-config discovered) existing)
               (if existing (u/deep-merge discovered existing) discovered))
     :notes @notes}))

;; ---------------------------------------------------------------- render

(def section-order
  [[:executors "global per-executor settings"]
   [:extra-providers "model providers; keys are !bw:// / $ENV refs, never literals"]
   [:mcps "MCP servers, fanned out to the listed tools"]
   [:skill-packs "where skills come from (git or local checkout)"]
   [:skills "installed skills"]
   [:memory "shared memory files linked into each agent"]
   [:projects "per-project overrides"]])

(defn- pp [v]
  (with-out-str
    (binding [clojure.pprint/*print-right-margin* 100]
      (clojure.pprint/pprint v))))

(defn- source-key
  "The keyed-section selector for a URI: `[:gh \"owner/repo\"]` for GitHub."
  [uri]
  (if-let [[_ repo] (re-find #"^(?:https://|git@)github\.com[/:]([^/]+/[^/]+?)(?:\.git)?/?$" (str uri))]
    [:gh repo]
    [:uri uri]))

(defn- key-id [[_ arg]]
  (keyword (fs/file-name (str/replace (str arg) #"/+$" ""))))

(defn- keyed-entry
  "Entity value for a keyed section: `:tools` as `:acli`, an `:alias` only when
   the key alone would derive a different id."
  [k id decl]
  (cond-> (dissoc decl :scope :tools :for :uri :path :from :id)
    (not= id (key-id k)) (assoc :alias (symbol (name id)))
    (or (:tools decl) (:for decl)) (assoc :acli (let [t (or (:tools decl) (:for decl))]
                                                  (if (keyword? t) [t] (vec t))))))

(defn- keyed-skills
  "Legacy `:skill-packs` / `:skills` maps as the selector-keyed DSL maps."
  [config placements]
  (let [packs (:skill-packs config)
        skills (:skills config)]
    (cond-> {}
      (seq packs)
      (assoc :skill-packs
             (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                   (for [[id decl] packs :let [k (source-key (:uri decl))]]
                     [k (keyed-entry k id decl)])))
      (seq skills)
      (assoc :skills
             (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                   (for [[id decl] skills
                         :let [k (if (:from decl)
                                   [(symbol (name (:from decl))) (or (:subdir decl) (name id))]
                                   [:uri (:path decl)])]]
                     [k (-> (keyed-entry k id (dissoc decl :subdir))
                            (assoc :in (placements :skills id decl)))]))))))

(defn entity-config
  "Import emits literal selectors; it cannot infer a user's intended tree."
  [config]
  (if (scope/dsl? config) config
      (let [projects (:projects config)
            placements (fn [kind id decl]
                         (let [ps (for [[_ p] projects :when (some #{id} (get p kind))] (:path p))]
                           (if (or (= :global (:scope decl)) (empty? ps)) :all (vec ps))))]
        (merge
         (apply dissoc config [:executors :cli-code :projects :mcps :skills :skill-packs :providers :extra-providers :memory])
         {:settings (vec (concat
                          (for [[t s] (or (:executors config) (:cli-code config))]
                            (assoc s :id (keyword (str "user-" (name t))) :tools [t] :in :all))
                          (for [[pid p] projects [t s] (:executors p)]
                            (assoc s :id (keyword (str (name pid) "-" (name t))) :tools [t] :in (:path p)))))
          :trust (vec (for [[id p] projects :when (some? (:trusted p))]
                        {:id id :in (:path p) :trusted (:trusted p) :tools (:tools p)}))}
         (keyed-skills config placements)
         (into {} (for [kind [:mcps :providers :memory]
                        :let [rows (if (= kind :providers) (merge (:extra-providers config) (:providers config)) (get config kind))]
                        :when (seq rows)]
                    [kind (vec (for [[id decl] rows
                                     :let [decl (if (string? decl) {:cmd decl} decl)]]
                                 (cond-> (assoc (dissoc decl :scope) :id id
                                                :in (placements (case kind :mcps :mcp nil) id decl))
                                   (:scope decl) (assoc :at (if (= :project (:scope decl)) :repo :machine)))))]))
         (when-let [ps (seq (for [[id p] projects :when (:permissions p)]
                             (assoc (:permissions p) :id id :in (:path p))))]
           {:permissions (vec ps)})))))

(defn render [config]
  (let [config (entity-config config)]
  (str
   ";; agents.edn — declarative coding-agent configuration.\n"
   ";; Managed by `agentctl` (apply / apply! / validate / import).\n"
   ";; Generated " (u/timestamp) " — review before applying.\n"
   "{\n"
   (str/join
    "\n"
    (for [[k doc] section-order
          :when (contains? config k)]
      (str " ;; " doc "\n"
           " " k "\n"
           (str/join "\n" (map #(str " " %) (str/split-lines (pp (get config k)))))
           "\n")))
   (str/join "\n"
             (for [[k v] config
                   :when (not (some #{k} (map first section-order)))]
               (str " " k "\n " (str/trim (pp v)) "\n")))
   "}\n")))
