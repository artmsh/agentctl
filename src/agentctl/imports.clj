(ns agentctl.imports
  "Reverse direction: read the live environment and emit agents.edn.

   Secrets are never emitted literally — every credential-shaped value becomes
   a !bw:// placeholder and is reported in the notes so the vault item can be
   created before the next apply."
  (:require [agentctl.adapters.antigravity :as antigravity]
            [agentctl.adapters.claude :as claude]
            [agentctl.adapters.codex :as codex]
            [agentctl.adapters.dsh :as dsh]
            [agentctl.codex-schema :as codex-schema]
            [agentctl.config :as config]
            [agentctl.adapters.kimi :as kimi]
            [agentctl.adapters.llm :as llm]
            [agentctl.adapters.omp :as omp]
            [agentctl.adapters.pi :as pi]
            [agentctl.refs :as refs]
            [agentctl.scope :as scope]
            [agentctl.state :as state]
            [agentctl.statusline :as statusline]
            [agentctl.toml :as toml]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [clojure.pprint]
            [clojure.string :as str]))

(def notes (atom []))
(defn- note! [s] (swap! notes conj s) nil)

(defn- env-ref?
  "A whole `$VAR` reference: it names where a value lives, and is no secret."
  [v]
  (and (string? v)
       (= :env (:ref/kind (try (refs/parse v) (catch Exception _ nil))))))

(defn- redact-headers
  "Every header value as a bw placeholder. An http server's whole credential
   rides in a header, under any name — `x-docs` as readily as
   `Authorization` — and agentctl writes the resolved value, so nothing on
   disk says which header held a secret: every one is treated as if it did.
   A whole `$VAR` reference is kept."
  [scope m]
  (into {}
        (map (fn [[k v]]
               [k (if (env-ref? v)
                    v
                    (do (note! (str "redacted " scope "." (u/kw->str k)
                                    " -> create bitwarden item `" scope "` field `" (u/kw->str k) "`"))
                        (refs/redact scope k)))]))
        m))

(def ^:private plain-env-words
  "Values common enough as settings, and short enough, to be no one's secret."
  #{"true" "false" "yes" "no" "on" "off" "enabled" "disabled"
    "trace" "debug" "info" "warn" "warning" "error" "fatal" "silent" "verbose" "quiet"
    "fast" "slow" "dev" "development" "prod" "production" "test" "staging" "local"})

(defn- plain-env-value?
  "Whether an env value is provably no secret: a reference to where the value
   lives, a small number, or a common settings word. agentctl writes env
   resolved, so anything else read back may be a secret under any name."
  [v]
  (or (boolean? v)
      (and (number? v) (< -100000 v 100000))
      (and (string? v)
           (or (env-ref? v)
               (re-matches #"-?\d{1,5}" v)
               (contains? plain-env-words (str/lower-case v))))))

(defn- redact-env
  "Every env value as a bw placeholder, unless it is provably no secret
   (`plain-env-value?`). A credential-shaped name is the weak test: a token
   under `DOCS_AUTH` or inside `UPSTREAM` looks like nothing, and import
   writes a file people commit."
  [scope m]
  (into {}
        (map (fn [[k v]]
               [k (if (plain-env-value? v)
                    v
                    (do (note! (str "redacted " scope "." (u/kw->str k)
                                    " -> create bitwarden item `" scope "` field `" (u/kw->str k) "`"))
                        (refs/redact scope k)))]))
        m))

(defn- credential-flag
  "The name a credential-looking flag carries — `api-key` for `--api-key`,
   `token` for `-token` — or nil. A flag naming where the credential lives
   (`--token-file`, `--api-key-env`) takes a path or a name, and is not one."
  [a]
  (when-let [[_ nm] (and (string? a) (re-matches #"--?([A-Za-z][A-Za-z0-9_-]*)" a))]
    (when (and (re-find refs/secret-key-pattern nm)
               (not (re-find refs/indirection-key-pattern nm))
               (not (re-find #"(?i)[-_](file|path|dir|var|name)$" nm)))
      nm)))

(defn- redact-args
  "An argument after a credential-looking flag (`--token X`, `--token=X`) as
   a `$VAR` reference: argv is written literally, so a `!bw://` placeholder
   would reach the server as text, while `$VAR` is expanded when agentctl
   reads the config. The variable is named after the server and the flag.
   A whole `$VAR` already there is kept."
  [scope args]
  (let [secret (fn [flag v]
                 (if (env-ref? v)
                   v
                   (let [var (-> (str (u/kw->str scope) "_" flag)
                                 (str/replace #"[^A-Za-z0-9]+" "_")
                                 str/upper-case)]
                     (note! (str "redacted " scope " argument after --" flag " -> set $" var))
                     (str "$" var))))]
    (first
     (reduce (fn [[out flag] a]
               (if-let [[_ f v] (and (string? a) (re-matches #"(--?[A-Za-z][A-Za-z0-9_-]*)=(.+)" a))]
                 [(conj out (if-let [nm (credential-flag f)] (str f "=" (secret nm v)) a)) nil]
                 (if (and flag (string? a) (not (str/starts-with? a "-")))
                   [(conj out (secret flag a)) nil]
                   [(conj out a) (credential-flag a)])))
             [[] nil]
             args))))

(defn- invert [key-map] (into {} (map (fn [[k v]] [(u/kw->str v) k])) key-map))

;; ---------------------------------------------------------------- settings

(def ^:private keyed-by-data
  "Profile tables whose keys are data (paths, hosts), not field names."
  #{"filesystem" "domains" "workspace_roots" "unix_sockets"})

(defn- import-profile
  "A `[permissions.<name>]` table as agents.edn declares it: field names as
   keywords, paths and hosts as the strings they are."
  ([m] (import-profile m false))
  ([m data-keys?]
   (into {} (map (fn [[k v]]
                   [(if data-keys? k (keyword k))
                    (if (map? v) (import-profile v (or data-keys? (contains? keyed-by-data k))) v)]))
         m)))

(def ^:private codex-hook-fields
  (into {} (map (fn [[k v]] [v k])) (merge codex-schema/hook-entry-keys {:type :type})))

(defn- codex-hook-decl
  "A `hooks[event]` element as the declaration that would write it."
  [event group]
  (let [h (first (:hooks group))]
    (u/prune-nils
     (merge {:event (keyword (name event)) :matcher (:matcher group)}
            (into {} (keep (fn [[k v]] (when-let [dk (get codex-hook-fields k)]
                                         (when (not= :type dk) [dk v]))))
                  h)))))

(defn scan-codex-hooks
  "Codex hooks agentctl owns (recorded in its state, still in hooks.json),
   rebuilt as declarations. Everything else in hooks.json was installed by a
   vendor or by hand; import leaves it out and says how much, since
   declaring it would make agentctl prune it later."
  []
  (let [file (u/read-json codex/hooks-file)
        st (state/load-state)
        groups (for [[event gs] (:hooks file) g gs] [event g])
        owned (into {} (for [id (state/managed-ids st :codex :hooks)
                             :let [{:keys [path value]} (state/entry st :codex :hooks id)]
                             :when (some (fn [[event g]] (and (= (name (last path)) (name event))
                                                              (= (u/norm g) (u/norm value))))
                                         groups)]
                         [(keyword id) (codex-hook-decl (last path) value)]))
        vendor (- (count groups) (count owned))]
    (when (pos? vendor)
      (note! (str vendor " codex hook group(s) in " (u/tilde codex/hooks-file)
                  " are not agentctl's — left out; declare one under :hooks to own it")))
    owned))

(defn scan-settings []
  (u/prune-nils
   {:claude (let [s (u/read-json claude/settings-file)
                  inv (invert claude/setting-keys)]
              (not-empty (into {} (keep (fn [[k v]] (when-let [dk (get inv (name k))]
                                                      (cond
                                                        ;; agentctl writes env resolved: a
                                                        ;; credential comes back as a placeholder,
                                                        ;; and leaving env out would have the
                                                        ;; next apply! prune it
                                                        (= :env dk)
                                                        (when (and (map? v) (seq v))
                                                          [dk (redact-env "claude-env"
                                                                          (into (sorted-map) (map (fn [[ek ev]] [(u/key-str ek) ev])) v))])
                                                        (map? v) nil
                                                        :else [dk v]))))
                               s)))
    :codex (let [t (toml/read-toml-lenient codex/config-file)
                 inv (invert codex/setting-keys)
                 hooks (scan-codex-hooks)]
             (not-empty
              (cond-> (into {} (keep (fn [[k v]] (when-let [dk (get inv k)]
                                                  (when (string? v) [dk v]))))
                            t)
                (seq (get-in t ["tui" "keymap"]))
                (assoc :keymap (get-in t ["tui" "keymap"]))
                (seq hooks) (assoc :hooks hooks)
                (map? (get t "permissions"))
                (assoc :permission-profiles (into (sorted-map-by #(compare (str %1) (str %2)))
                                                  (for [[nm p] (get t "permissions") :when (map? p)]
                                                    [(if (re-matches #"[A-Za-z][A-Za-z0-9_-]*" nm) (keyword nm) nm)
                                                     (import-profile p)])))
                (sequential? (get-in t ["tui" "status_line"]))
                (assoc :status-line (mapv keyword (get-in t ["tui" "status_line"])))
                ;; slots are digits; EDN reads best with them as numbers
                (seq (get-in t ["tui" "presets"]))
                (assoc :presets (into (sorted-map-by #(compare (str %1) (str %2)))
                                      (for [[n p] (get-in t ["tui" "presets"])]
                                        [(or (parse-long n) n)
                                         (into {} (map (fn [[k v]] [(keyword k) v])) p)]))))))
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
    :kimi (let [t (toml/read-toml-lenient kimi/config-file)
                inv (invert kimi/setting-keys)]
            (not-empty
             (into {} (keep (fn [[k v]] (when-let [dk (get inv k)]
                                             (when (or (string? v) (boolean? v)) [dk v]))))
                   t)))
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
    :serverUrl :disabled
    ;; the token's env name, under codex's spelling and the translated one
    :bearer_token_env_var})

(defn- normalize-entry [tool nm entry]
  (let [t (or (:transport entry) entry)
        extra (not-empty (into {} (remove (fn [[k _]] (contains? modelled-mcp-keys k))) entry))]
    (u/prune-nils
     {:tool tool
      :command (or (:command t) (:command entry))
      ;; a token passed on the command line is as much a secret as one in env
      :args (not-empty (redact-args nm (or (:args t) (:args entry))))
      :url (or (:url t) (:url entry))
      :env (not-empty (redact-env nm (or (:env t) (:env entry))))
      ;; an http server's whole credential rides in a header — left
      ;; unmodelled it would land in :extra verbatim
      :headers (not-empty (redact-headers nm (or (:headers t) (:http_headers t) (:headers entry))))
      ;; Codex exposes this as a variable name, never the token value.
      :bearer-token-env (or (:bearer_token_env_var t) (:bearer_token_env_var entry))
      ;; Codex's env_http_headers and http_headers_helper have no DSL shape or
      ;; write path yet; they remain unsupported import fields.
      :enabled (if (false? (:enabled entry)) false nil)
      :cwd (or (:cwd t) (:cwd entry))
      ;; keys agentctl does not model are round-tripped verbatim
      :extra extra})))

(defn- antigravity-mcp-entry
  "Antigravity spells the endpoint `serverUrl` and inverts the flag; translated
   here so one server declared for several tools still collapses into a single
   entry instead of a :per-tool split."
  [nm v]
  (normalize-entry :antigravity nm
                   (cond-> (dissoc v :serverUrl :disabled)
                     (:serverUrl v) (assoc :url (:serverUrl v))
                     (true? (:disabled v)) (assoc :enabled false))))

(defn- kimi-mcp-entry
  "Kimi spells the token's env name `bearerTokenEnvVar`; translated into the
   DSL's `bearer-token-env` so an imported server keeps its credential without
   agentctl having to know Kimi's spelling of it."
  [nm v]
  (normalize-entry :kimi nm
                   (cond-> (dissoc v :bearerTokenEnvVar)
                     (:bearerTokenEnvVar v) (assoc :bearer_token_env_var (:bearerTokenEnvVar v)))))

(defn scan-mcps []
  (let [named (concat
               (for [[k v] (claude/current-mcps)] [(name k) (normalize-entry :claude (name k) v)])
               (for [[nm v] (codex/mcp-list)] [(name nm) (normalize-entry :codex (name nm) v)])
               (for [[k v] (:mcpServers (u/read-json pi/mcp-file))] [(name k) (normalize-entry :pi (name k) v)])
               (for [[k v] (:mcpServers (u/read-json omp/mcp-file))] [(name k) (normalize-entry :omp (name k) v)])
               (for [[k v] (:mcpServers (u/read-json antigravity/mcp-file))]
                 [(name k) (antigravity-mcp-entry (name k) v)])
               (for [[k v] (:mcpServers (u/read-json kimi/mcp-file))]
                 [(name k) (kimi-mcp-entry (name k) v)]))]
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

(defn- kimi-provider-models
  "Kimi's model aliases, grouped by the provider they point at.

   A Kimi model is a table of its own: it names its provider, the upstream
   model id, and the capabilities Kimi needs to price and route it. The DSL
   keeps a provider's models beside it, so the alias becomes the model's id
   and its capabilities become the declared ones."
  [cfg]
  (let [models (get cfg "models")]
    (when (map? models)
      (reduce
       (fn [acc [alias m]]
         (if-not (map? m)
           acc
           (if-let [pid (get m "provider")]
             (do
               ;; the DSL keeps one id per model, where Kimi has an alias and
               ;; an upstream id; a difference between them cannot survive the
               ;; round trip, and saying so beats changing the route silently
               (when-let [upstream (get m "model")]
                 (when (not= upstream alias)
                   (note! (str "kimi model " alias " points at upstream model " upstream
                               " — agents.edn keeps one id per model, so it carries "
                               alias))))
               (update acc pid (fnil conj [])
                       {:id alias
                        :caps (kimi/import-caps m)}))
             acc)))
       {}
       models))))

(defn scan-providers []
  (let [codex-cfg (toml/read-toml-lenient codex/config-file)
        kimi-cfg (toml/read-toml-lenient kimi/config-file)
        kimi-models (kimi-provider-models kimi-cfg)
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
                 (for [[nm p] (get kimi-cfg "providers")]
                   {:id (keyword (name nm)) :tool :kimi
                    :url (get p "baseUrl")
                    ;; an apiKeyEnv is the env var's name, never the token
                    :key (cond
                           (get p "apiKeyEnv") (symbol (str "$" (get p "apiKeyEnv")))
                           (get p "apiKey")
                           (do (note! (str "kimi provider " (name nm)
                                           " has a plaintext apiKey — emitted as !bw:// placeholder"))
                               (refs/redact (str "provider-" (name nm)) :apiKey)))
                    :api (kimi/import-api (get p "type"))
                    :models (get kimi-models nm)})
                 (for [[base es] (group-by :api_base (or (u/read-yaml (llm/models-file)) []))
                       :when base]
                   {:id (provider-id base nil) :tool :llm
                    :url base
                    :key-name (:api_key_name (first es))
                    :models (vec (sort (map :model_id es)))}))
        ;; Native files can contain built-in provider overrides (for example a
        ;; Pi apiKey without baseUrl). The DSL requires an endpoint; importing
        ;; such a row would make the entire emitted config refuse to plan.
        entries (into [] (keep (fn [{:keys [id tool url] :as entry}]
                                 (if (str/blank? (str url))
                                   (note! (str "skipped " (name tool) " provider " (name id)
                                               " without a base URL — built-in provider overrides are not supported"))
                                   entry)))
                      entries)
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
   :antigravity antigravity/skills-dir
   :kimi kimi/skills-dir
   :dsh dsh/skills-dir})

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
   :antigravity antigravity/memory-file
   :kimi kimi/memory-file
   :dsh dsh/memory-file})

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

(declare scan-projects)

(defn- marketplace-source
  "`[key ref]` for a declared marketplace: the keyed-section selector and the
   git ref it is pinned to, if any. A directory is declared as `file://`, the
   spelling agentctl adds by path again."
  [m]
  (let [source (:source m)]
    (cond
      (and (= "github" (:source source)) (:repo source)) [[:gh (:repo source)] (:ref source)]
      (and (= "directory" (:source source)) (:path source)) [[:uri (str "file://" (u/tilde (:path source)))] nil]
      (and (= "url" (:source source)) (:url source)) [[:uri (:url source)] nil]
      (:repo source) [[:gh (:repo source)] (:ref source)]
      (:url source) [[:uri (:url source)] (:ref source)]
      :else nil)))

(defn- plugin-declarations [settings placement at]
  (let [marketplaces (or (:extraKnownMarketplaces settings) {})
        enabled (or (:enabledPlugins settings) {})]
    (keep (fn [[raw enabled?]]
            (when (not= false enabled?)
              (let [[plugin marketplace] (str/split (u/key-str raw) #"@" 2)
                    [source ref] (marketplace-source (get marketplaces (keyword marketplace)))]
                (when (and (not (str/blank? plugin)) (not (str/blank? marketplace)) source)
                  [(conj source plugin)
                   (cond-> {:acli [:claude] :in placement}
                     ;; the marketplace name defaults to the plugin's
                     (not= plugin marketplace) (assoc :marketplace marketplace)
                     ref (assoc :ref ref)
                     at (assoc :at at))]))))
          enabled)))

(defn scan-plugins
  "Read Claude's marketplace and enabledPlugins settings into keyed DSL rows.
   Project rows are discovered from the same trusted-project inventory import
   uses for the rest of Claude's project configuration."
  []
  (let [global (plugin-declarations (u/read-json claude/settings-file) :all nil)
        trusted (scan-projects)
        discovered (for [p (scope/repositories (str u/home "/projects"))]
                     [(keyword (str (fs/file-name p))) {:path (u/tilde p)}])
        projects (merge (into {} discovered) trusted)
        project-rows (mapcat (fn [[_ p]]
                              (concat
                               (when (u/exists? (claude/project-settings-file p))
                                 (plugin-declarations (u/read-json (claude/project-settings-file p))
                                                      (:path p) :repo))
                               (let [f (str (:path p) "/.claude/settings.local.json")]
                                 (when (u/exists? f)
                                   (plugin-declarations (u/read-json f) (:path p) :machine)))))
                            projects)]
    (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
          (concat global project-rows))))

(declare scan-projects)

(defn- import-packs
  "Packs a subagent link can point into: the existing config's, then those
   the skill scan found, as `{id {:root …}}`."
  [existing scanned]
  (merge (into {} (for [[id decl] scanned] [id (config/norm-pack id decl)]))
         (when existing
           (try (:skill-packs (config/normalize existing "import"))
                (catch Exception _ nil)))))

(defn scan-agents
  "Claude subagent definitions linked into a known pack's `agents/`, as
   `:agents` rows: user-wide is `:in :all`, a project's is its path. A plain
   file, or a link to anywhere else, is the user's own and is not imported."
  [packs]
  (let [dirs (into {} (for [[id p] packs
                            :let [d (some-> (:root p) (str "/agents"))]
                            :when (and d (u/exists? d))]
                        [(u/real-path d) id]))
        found (fn [dir in]
                (for [f (when (fs/directory? dir) (sort (map str (fs/glob dir "*.md"))))
                      :when (fs/sym-link? f)
                      :let [real (u/real-path f)
                            pid (get dirs (str (fs/parent real)))]
                      :when (and pid (= (fs/file-name real) (fs/file-name f)))]
                  {:pack pid :name (str/replace (str (fs/file-name f)) #"\.md$" "") :in in}))
        projects (distinct (concat (map (comp :path val) (scan-projects))
                                   (map u/tilde (scope/repositories (str u/home "/projects")))))
        rows (concat (found (str claude/home "/agents") :all)
                     (mapcat #(found (str (u/expand %) "/.claude/agents") (u/tilde (u/expand %))) projects))]
    (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
          (for [[[pid nm] rs] (group-by (juxt :pack :name) rows)
                :let [ins (map :in rs)]]
            [[(symbol (name pid)) nm]
             {:in (if (some #{:all} ins) :all (vec (sort (distinct ins)))) :acli [:claude]}]))))

(defn scan-projects []
  (let [codex-cfg (toml/read-toml-lenient codex/config-file)
        codex-projects (for [[path v] (get codex-cfg "projects")]
                         [path (= "trusted" (get v "trust_level")) :codex])
        pi-trust (for [[k v] (u/read-json pi/trust-file)] [(u/key-str k) (boolean v) :pi])
        claude-projects (for [[k v] (:projects (u/read-json claude/runtime-file))
                              :when (:hasTrustDialogAccepted v)]
                          [(u/key-str k) true :claude])
        agy-trust (for [p (:trustedWorkspaces (u/read-json antigravity/settings-file))]
                    [(str p) true :antigravity])
        all (group-by first (concat codex-projects pi-trust claude-projects agy-trust))
        trusted-paths (for [[path entries] all
                            ;; "/" and $HOME are trust artefacts, not projects
                            :when (and (some second entries)
                                       (not (contains? #{"/" u/home ""} path)))]
                        path)
        ;; In the entity DSL, a directory that is not a repository root means
        ;; *every* repository below it. Importing a native trust entry for
        ;; ~/projects as :in ~/projects would broaden one entry into dozens.
        interesting (filter #(scope/repo? (u/abs-path %)) trusted-paths)
        skipped (remove #(scope/repo? (u/abs-path %)) trusted-paths)
        _ (when (seq skipped)
            (note! (str "skipped " (count skipped)
                        " trusted non-repository path(s); :in would expand them to child repositories")))
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
                 :permissions (not-empty (:permissions (u/read-json
                                                       (claude/project-settings-file {:path path}))))
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
                     :plugins (not-empty (scan-plugins))
                     :agents (not-empty (scan-agents (import-packs existing packs)))
                     :skills (not-empty skills)
                     :skill-packs (not-empty packs)
                     :extra-providers (not-empty (scan-providers))
                     :memory (not-empty (scan-memory))
                     :projects (not-empty (scan-projects))
                     ;; read back, or an import that loses it makes the next
                     ;; apply! delete the file agentctl owns
                     :statusline (statusline/scan)})]
    {:config (if (scope/dsl? existing)
               (cond-> (merge-with (fn [a b]
                                     (if (and (vector? a) (vector? b))
                                       (let [ids (set (keep :id b))]
                                         (vec (concat (remove #(ids (:id %)) a) b))) b))
                                   (entity-config discovered) existing)
                 ;; a link found on disk joins the declared ones; a declared
                 ;; one keeps its value
                 (scope/keyed? (:agents discovered))
                 (assoc :agents (let [declared (set (for [[[pack arg] _] (:agents existing)
                                                          nm (if (string? arg) [arg] arg)]
                                                      [(name pack) nm]))]
                                  (merge (into {} (remove (fn [[[pack nm] _]] (declared [(name pack) nm])))
                                               (:agents discovered))
                                         (:agents existing)))))
               (if existing (u/deep-merge discovered existing) discovered))
     :notes @notes}))

;; ---------------------------------------------------------------- render

(def section-order
  [[:executors "global per-executor settings"]
   [:extra-providers "model providers; keys are !bw:// / $ENV refs, never literals"]
   [:mcps "MCP servers, fanned out to the listed tools"]
   [:plugins "Claude Code marketplaces and enabled plugins"]
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
                   (for [[id decl] packs
                         :let [k (source-key (:uri decl))
                               in (placements :skills id decl)]]
                     [k (cond-> (keyed-entry k id decl)
                          (not= :all in) (assoc :in in))])))
      (seq skills)
      (assoc :skills
             (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                   (for [[id decl] skills
                         :let [k (if (:from decl)
                                   [(symbol (name (:from decl))) (or (:subdir decl) (name id))]
                                   [:uri (:path decl)])]]
                     [k (-> (keyed-entry k id (dissoc decl :subdir))
                            (assoc :in (placements :skills id decl)))]))))))

(defn- keyed-plugins [config placements]
  (let [plugins (:plugins config)]
    (when (seq plugins)
      {:plugins
       (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
             (for [[id decl] plugins
                   :let [src (or (:repo decl) (:uri decl))
                         base (if (:repo decl) [:gh src] [:uri src])
                         k (conj base (or (:plugin decl) (name id)))
                         in (placements :plugins id decl)
                         at (when (and (vector? in) (seq in))
                              (if (= :project (:scope decl)) :repo :machine))]]
               [k (cond-> (dissoc (keyed-entry k id decl) :repo :uri :plugin)
                    (not= :all in) (assoc :in in)
                    at (assoc :at at))]))})))

(declare legacy-entity-config)

(defn entity-config
  "Import emits literal selectors; it cannot infer a user's intended tree.
   Sections import already reads as keyed rows ride along unchanged, so they
   do not hide the legacy sections beside them from the conversion."
  [config]
  (let [rows (into {} (for [k [:plugins :agents] :let [v (get config k)] :when (scope/keyed? v)] [k v]))
        others (apply dissoc config (keys rows))]
    (cond
      (empty? rows) (legacy-entity-config config)
      (or (empty? others) (scope/dsl? others)) config
      :else (merge (legacy-entity-config others) rows))))

(defn- legacy-entity-config [config]
  (if (scope/dsl? config) config
      (let [normalized (config/normalize config "import")
            resolved (:projects normalized)
            projects (into {} (for [[id p] (:projects config)]
                                [id (assoc p :path (get-in resolved [id :path]))]))
            placements (fn [kind id decl]
                         (let [ps (for [[_ p] resolved :when (contains? (get p kind) id)] (:path p))]
                           (if (or (= :global (:scope decl)) (empty? ps)) :all (vec ps))))
            ;; Legacy projects can name a skill without a declaration. Keep
            ;; the pack that normalization resolved for it, and retain the
            ;; inferred CLI selection for project skills and whole packs.
            config (update config :skills
                           #(into {} (for [[id s] (:skills normalized)
                                           :let [decl (or (get % id) (select-keys s [:from :subdir :mode]))]]
                                       [id (cond-> decl
                                             (and (= :project (:scope s)) (not (or (:tools decl) (:for decl))))
                                             (assoc :tools (vec (sort (:tools s)))))])))
            config (update config :skill-packs
                           #(into {} (for [[id decl] %
                                           :let [tools (set (mapcat (comp keys :tools val)
                                                                    (filter (fn [[_ p]] (contains? (:skills p) id)) resolved)))]]
                                       [id (cond-> decl
                                             (and (seq tools) (not (or (:tools decl) (:for decl))))
                                             (assoc :tools (vec (sort tools))))])))]
        (merge
         (apply dissoc config [:executors :cli-code :projects :mcps :plugins :skills :skill-packs :providers :extra-providers :memory])
         {:settings (vec (concat
                          (for [[t s] (or (:executors config) (:cli-code config))]
                            (assoc s :id (keyword (str "user-" (name t))) :tools [t] :in :all))
                          (for [[pid p] projects [t s] (when (map? (:executors p)) (:executors p))]
                            (assoc s :id (keyword (str (name pid) "-" (name t))) :tools [t] :in (:path p)))))
          :trust (vec (for [[id p] projects :when (some? (:trusted p))]
                        {:id id :in (:path p) :trusted (:trusted p)
                         :tools (vec (sort (get-in resolved [id :for-tools])))}))}
         (keyed-skills config placements)
         (keyed-plugins config placements)
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
