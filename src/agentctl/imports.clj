(ns agentctl.imports
  "Reverse direction: read the live environment and emit agents.edn.

   Secrets are never emitted literally — every credential-shaped value becomes
   a !bw:// placeholder and is reported in the notes so the vault item can be
   created before the next apply."
  (:require [agentctl.adapters.claude :as claude]
            [agentctl.adapters.codex :as codex]
            [agentctl.adapters.omp :as omp]
            [agentctl.adapters.pi :as pi]
            [agentctl.refs :as refs]
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
             (not-empty (into {} (keep (fn [[k v]] (when-let [dk (get inv k)]
                                                     (when (string? v) [dk v]))))
                              t)))
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
              :thinking (:defaultThinkingLevel c)})))}))

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
  #{:command :args :url :env :enabled :type :transport :name :disabled_reason
    :auth_status :startup_timeout_sec :tool_timeout_sec :cwd})

(defn- normalize-entry [tool nm entry]
  (let [t (or (:transport entry) entry)
        extra (not-empty (into {} (remove (fn [[k _]] (contains? modelled-mcp-keys k))) entry))]
    (u/prune-nils
     {:tool tool
      :command (or (:command t) (:command entry))
      :args (not-empty (vec (or (:args t) (:args entry))))
      :url (or (:url t) (:url entry))
      :env (not-empty (redact-map nm (or (:env t) (:env entry))))
      :enabled (if (false? (:enabled entry)) false nil)
      :cwd (or (:cwd t) (:cwd entry))
      ;; keys agentctl does not model are round-tripped verbatim
      :extra extra})))

(defn scan-mcps []
  (let [named (concat
               (for [[k v] (claude/current-mcps)] [(name k) (normalize-entry :claude (name k) v)])
               (for [[nm v] (codex/mcp-list)] [(name nm) (normalize-entry :codex (name nm) v)])
               (for [[k v] (:mcpServers (u/read-json pi/mcp-file))] [(name k) (normalize-entry :pi (name k) v)])
               (for [[k v] (:mcpServers (u/read-json omp/mcp-file))] [(name k) (normalize-entry :omp (name k) v)]))]
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
                    :api (:api p)}))
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
   :omp omp/skills-dir})

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
   :omp omp/memory-file})

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
        all (group-by first (concat codex-projects pi-trust claude-projects))
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
    {:config (if existing (u/deep-merge discovered existing) discovered)
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

(defn render [config]
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
   "}\n"))
