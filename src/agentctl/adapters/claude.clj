(ns agentctl.adapters.claude
  "Claude Code adapter.

   settings.json is agentctl-editable (plain config). ~/.claude.json is a large
   runtime state blob owned by the CLI: it is read for current state, and only
   ever mutated through `claude mcp` or narrowly-scoped project keys."
  (:require [agentctl.adapters.common :as common]
            [agentctl.plan :as plan]
            [agentctl.refs :as refs]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def tool :claude)
(def home (str u/home "/.claude"))
(def settings-file (str home "/settings.json"))
(def runtime-file (str u/home "/.claude.json"))
(def skills-dir (str home "/skills"))
(def memory-file (str home "/CLAUDE.md"))
(def settings-schema-url "https://json.schemastore.org/claude-code-settings.json")

(defn claude-sh
  "Run the claude CLI against the home directory agentctl is managing."
  [& args]
  (u/sh-env {:HOME u/home} args))

(defn claude-sh-in
  "Run the claude CLI with a project as its cwd and the managed HOME."
  [dir & args]
  (u/sh-env-in dir {:HOME u/home} args))

(defn- hide
  "`s` with each of `values` replaced by its mask, the longest first so a
   value that contains another is not left half shown."
  [s values]
  (reduce (fn [s v] (str/replace s v (refs/mask v)))
          (str s)
          (sort-by (comp - count) (distinct (remove str/blank? (map str values))))))

(defn- ok!
  "Turn a non-zero CLI exit into a real failure, so apply! reports the op as
   failed and claims no ownership of what the CLI never wrote — a server it
   refused to add, a plugin it never installed.

   The message names the command, and `claude mcp add` carries its env and
   header values on the command line, where the CLI may echo them back:
   `secrets` are masked wherever they appear, since the message is printed,
   and served to the GUI."
  ([result argv] (ok! result argv nil))
  ([{:keys [exit err out]} argv secrets]
   (when-not (zero? exit)
     (let [argv (mapv #(hide % secrets) argv)]
       (throw (ex-info (str (str/join " " argv) " failed: "
                            (hide (first (remove str/blank? [err out])) secrets))
                       {:exit exit :argv argv}))))
   true))

(defn- claude-ok!
  "Run the claude CLI and fail on a non-zero exit."
  ([argv] (claude-ok! argv nil))
  ([argv secrets] (ok! (claude-sh argv) argv secrets)))

(def setting-keys
  {:model :model
   :output-style :outputStyle
   :theme :theme
   :effort :effortLevel
   :thinking :effortLevel
   :cleanup-period-days :cleanupPeriodDays
   :auto-compact :autoCompactEnabled
   :ultracode :ultracode
   :enable-workflows :enableWorkflows
   :skip-auto :skipAutoPermissionPrompt
   :status-line :statusLine
   :model-settings :modelSettings
   :env :env})

;; ---------------------------------------------------------------- settings

(defn- declared-settings
  "Declared key -> [native-key value], one entry per native key. Two spellings
   of one setting (`:effort` and `:thinking` are both effortLevel) would
   otherwise queue two writes to the same place. Which of the two wins is map
   order, so declaring both is a coin toss. Nothing warns about it today."
  [settings]
  (->> settings
       (keep (fn [[k v]] (when-let [nk (get setting-keys k)] [k nk v])))
       (reduce (fn [acc [k nk v]] (if (some (fn [[_ [n _]]] (= n nk)) acc)
                                    acc
                                    (assoc acc k [nk v])))
               {})))

(defn- env-setting-op
  "The `env` object of a settings file. Its values take the reference forms an
   MCP server's env does and resolve the same way: written as declared, a
   `!bw://` ref would land in the file as its own text. A ref that does not
   resolve holds the whole object back, as it does an MCP server, and a
   resolved secret is masked in the plan and flagged by `note`.

   `base` is what `plan/json-set-op` takes, less the value."
  [base env note]
  (let [{parsed :refs invalid :invalid}
        (reduce (fn [acc [k v]]
                  ;; a malformed ref is one key's problem, not the whole plan's
                  (try (assoc-in acc [:refs k] (refs/parse v))
                       (catch clojure.lang.ExceptionInfo e
                         (update acc :invalid conj {:key k :status :error :message (ex-message e)
                                                    :where "env"}))))
                {:refs {} :invalid []}
                env)
        resolved (common/resolve-env parsed)
        issues (concat invalid (common/issues-in "env" resolved))
        secrets (common/secret-keys parsed env)]
    (if (seq issues)
      (common/unresolved-op (-> (select-keys base [:tool :kind :id :project])
                                (assoc :target (:file base)))
                            issues)
      (when-let [op (plan/json-set-op (assoc base
                                             :value (:values resolved)
                                             :risk (if (seq secrets) :secret :low)
                                             :note (when (seq secrets) note)))]
        (cond-> op
          (seq (:diffs op))
          (update :diffs (partial mapv (fn [{:keys [before after] :as d}]
                                         (let [[b a] (common/mask-secrets before after secrets)]
                                           (assoc d :before b :after a))))))))))

(defn settings-ops [cfg]
  (let [settings (common/settings-for cfg tool)
        kvs (declared-settings settings)
        unsupported (remove (conj (set (keys setting-keys)) :hooks :permissions) (keys settings))]
    (concat
     (keep identity
           (for [[bucket rules] (:permissions settings)]
             (plan/json-set-op {:tool tool :kind :permissions :id (keyword (str "permissions." (name bucket)))
                                :file settings-file :path [:permissions bucket] :value rules})))
     (keep identity
           [(plan/json-set-op {:tool tool :kind :settings :id :$schema
                               :file settings-file :path [:$schema] :value settings-schema-url
                               :summary "settings.json $schema"})])
     ;; keyed by the name agents.edn uses: a reader checking the plan against
     ;; the file they wrote should not have to translate back
     (for [[k [nk v]] kvs
           :let [base {:tool tool :kind :settings :id k
                       :file settings-file :path [nk]
                       :report-converged? true
                       :summary (str "settings.json " (name nk))}
                 op (if (= :env nk)
                      (env-setting-op base v "⚠ secret value written into ~/.claude/settings.json")
                      (plan/json-set-op (assoc base :value v)))]
           :when op]
       op)
     (for [k unsupported]
       (plan/op {:action :noop :tool tool :kind :settings :id k
                 :summary (str "unsupported setting " k " for claude — ignored")
                 :warn true})))))

;; ---------------------------------------------------------------- hooks

;; `:hooks` is a nested structure, not a scalar, so it is carved out of
;; `setting-keys`/`declared-settings` above and handled entirely here — see
;; `hook-ops`. `settings-ops` excludes it from its "unsupported" sweep by
;; name, or every declared hook would print a spurious ignored-setting warn.
(def hook-entry-keys
  "Declared hook field -> native `hooks[event][].hooks[]` field. `:event` and
   `:matcher` are consumed separately (they place the entry, they are not
   part of it); everything else here rides straight into the command object."
  {:type :type :command :command :timeout :timeout :async :async
   :async-rewake :asyncRewake :shell :shell :if :if
   :status-message :statusMessage :args :args})

(defn hook-command
  "One `hooks[event][].hooks[]` entry. Defaults to a command hook — the only
   kind agentctl's DSL spells out today; `:type` can override it for a prompt/
   agent/http/mcp hook, whose extra fields pass through via `hook-entry-keys`."
  [decl]
  (u/prune-nils
   (merge {:type "command"}
          (into {} (keep (fn [[k v]] (when-let [nk (get hook-entry-keys k)] [nk v]))) decl))))

(defn hook-group
  "One element of the `hooks[event]` array: a matcher (optional — omitted
   means the hook fires unconditionally) plus the one command it runs."
  [decl]
  (u/prune-nils {:matcher (:matcher decl) :hooks [(hook-command decl)]}))

(defn- hook-path [decl] [:hooks (keyword (u/kw->str (:event decl)))])

(defn hook-ops
  "Global hooks declared under `:executors :claude :hooks`. Each declared id
   owns exactly one array element (`json-array-merge-op`), located by value
   rather than index — a hook injected by another tool into the same event
   sits in the same array and is never touched, matched, or reported on.

   Update-in-place needs the element as it was last written, which an array
   cannot hand back by key — `core/inventory` records it (`:path`/`:value`)
   in the state manifest for exactly this lookup."
  [cfg st]
  (let [declared (:hooks (common/settings-for cfg tool))
        managed (into #{} (map keyword) (state/managed-ids st tool :hooks))]
    (concat
     (for [[id decl] declared
           :let [path (hook-path decl)
                 value (hook-group decl)
                 old (:value (state/entry st tool :hooks id))
                 op (plan/json-array-merge-op
                     {:tool tool :kind :hooks :id id
                      :file settings-file :path path :old old :value value
                      :report-converged? true
                      :summary (str "settings.json hooks." (name (last path))
                                    (when-let [m (:matcher decl)] (str " [" m "]")))})]
           :when op]
       op)
     (for [id managed
           :when (not (contains? declared id))
           :let [entry (state/entry st tool :hooks id)
                 op (when (and entry (not (:project-path entry)))
                      (plan/json-array-unset-op
                       {:tool tool :kind :hooks :id id
                        :file settings-file :path (:path entry) :old (:value entry)
                        :summary "removed from agents.edn"}))]
           :when op]
       op))))

;; ---------------------------------------------------------------- mcps

(defn current-mcps []
  (or (:mcpServers (u/read-json runtime-file)) {}))

(defn- key-names
  "Sorted names of an env or header map. Shapes compare names, never values:
   the value is a secret `claude mcp add` stores and nothing here reads back,
   while a missing name is a server that cannot authenticate."
  [m]
  (not-empty (vec (sort (map u/key-str (keys m))))))

(defn- entry-type
  "The transport an entry is written for. Claude Code infers it when `type`
   is absent, and so does this."
  [entry]
  (or (:type entry) (if (:url entry) "http" "stdio")))

(defn- desired-shape
  "What `claude mcp add` will write: env for a stdio server, headers for an
   http one — the other is never passed, so counting it would be drift that
   no apply can clear."
  [m]
  (let [http? (= :http (:transport m))]
    (u/prune-nils
     {:type (name (:transport m))
      :command (:command m)
      :args (not-empty (vec (:args m)))
      :url (:url m)
      :env (when-not http? (key-names (:env m)))
      :headers (when http? (key-names (:headers m)))})))

(defn- current-shape [entry]
  (u/prune-nils
   {:type (entry-type entry)
    :command (:command entry)
    :args (not-empty (vec (:args entry)))
    :url (:url entry)
    :env (key-names (:env entry))
    :headers (key-names (:headers entry))}))

(defn- resolve-mcp
  "A server's env and headers with every ref resolved, and the refs that did
   not resolve: a server holding one is not written, and not approved."
  [m]
  (let [env (common/resolve-env (:env m))
        headers (common/resolve-env (:headers m))]
    {:env env
     :headers headers
     :issues (concat (common/issues-in "env" env) (common/issues-in "headers" headers))}))

(defn mcp-ops [cfg st]
  (let [desired (common/for-tool-resources (common/global-mcps (:mcps cfg) tool) tool)
        current (current-mcps)
        managed (into #{} (map keyword) (state/managed-ids st tool :mcps))]
    (concat
     (for [[id m] desired
           :let [nm (u/kw->str id)
                 cur (get current (keyword nm))
                 want (desired-shape m)
                 have (some-> cur current-shape)
                 {:keys [env headers issues]} (resolve-mcp m)]
           ;; an unresolved secret is reported even when the shapes agree: the
           ;; value cannot be checked, so the run cannot call itself converged
           :when (or (seq issues) (not= (u/norm want) (u/norm have)))]
       (if (seq issues)
         (common/unresolved-op {:tool tool :kind :mcps :id id :target runtime-file} issues)
         (plan/op {:action (if cur :update :create)
                   :tool tool :kind :mcps :id id
                   :target runtime-file
                   :summary (common/mcp-summary m)
                   :note (when (common/holds-secret? (merge (:env m) (:headers m)))
                           "⚠ secret value stored in ~/.claude.json")
                   :diffs (plan/field-diffs (or have {}) want)
                   :risk (if (common/holds-secret? (merge (:env m) (:headers m))) :secret :low)
                   :exec! (fn []
                            (when cur (claude-ok! ["claude" "mcp" "remove" "--scope" "user" nm]))
                            (claude-ok!
                             (vec (if (= :http (:transport m))
                                    (concat ["claude" "mcp" "add" "--scope" "user"
                                             "--transport" "http" nm (:url m)]
                                            (mapcat (fn [[k v]] ["--header" (str k ": " v)]) (:values headers)))
                                    (concat ["claude" "mcp" "add" "--scope" "user" nm]
                                            (mapcat (fn [[k v]] ["-e" (str k "=" v)]) (:values env))
                                            ["--"] [(:command m)] (:args m))))
                             (concat (vals (:values env)) (vals (:values headers)))))})))
     ;; only user-wide servers: a namespaced id is a project's server, which
     ;; lives in that project's .mcp.json and is not ours to uninstall globally
     (for [id managed
           :when (and (nil? (namespace id))
                      (not (contains? desired id))
                      (get current (keyword (u/kw->str id))))]
       (plan/op {:action :delete :tool tool :kind :mcps :id id
                 :target runtime-file
                 :summary "removed from agents.edn"
                 :risk :medium
                 :exec! (fn [] (claude-ok! ["claude" "mcp" "remove" "--scope" "user" (u/kw->str id)]))})))))

;; ---------------------------------------------------------------- skills

(defn skill-ops
  "User-wide skills. A skill a project named lives in that project and is
   installed by `project-skill-ops`, not here."
  [cfg st]
  (let [desired (sources/for-tool cfg
                                  (into {} (filter (fn [[_ s]] (= :global (:scope s))))
                                        (sources/all-skills cfg))
                                  tool)
        managed (into #{} (map keyword) (state/managed-ids st tool :skills))]
    (concat
     (for [[id s] desired
           :when (:source s)
           :let [op (plan/link-op {:tool tool :kind :skills :id id
                                   :src (:source s)
                                   :dest (str skills-dir "/" (name id))
                                   :mode (:mode s)})]
           :when op]
       op)
     ;; a namespaced id is a project's skill and is not ours to remove from here
     (for [id managed
           :when (and (nil? (namespace id)) (not (contains? desired id)))
           :let [op (plan/unlink-op {:tool tool :kind :skills :id id
                                     :dest (str skills-dir "/" (name id))})]
           :when op]
       op))))

;; ---------------------------------------------------------------- memory

(defn memory-ops [cfg]
  (for [[id m] (common/for-tool (:memory cfg) tool)
        :when (= :global (:scope m))
        :let [op (plan/link-op {:tool tool :kind :memory :id id
                                :src (:from m) :dest memory-file :mode (:mode m)})]
        :when op]
    op))

;; ---------------------------------------------------------------- projects

(defn project-settings-file [proj] (str (:path proj) "/.claude/settings.json"))
(defn- project-mcp-file [proj] (str (:path proj) "/.mcp.json"))

(defn- plugin-scope [scope]
  (case scope :global "user" :local "local" :project "project" "user"))

(defn- plugin-settings-file
  ([scope] (if (= :global scope) settings-file nil))
  ([proj scope]
   (case scope
     :local (str (:path proj) "/.claude/settings.local.json")
     :project (str (:path proj) "/.claude/settings.json")
     settings-file)))

(defn- local-uri?
  "A `file://` URI or a path, as opposed to a remote URL or scp-style address."
  [uri]
  (or (str/starts-with? uri "file://")
      (not (re-find #"^[a-z+]+://|^[^/]+@[^:/]+:" uri))))

(defn- uri-path [uri] (u/abs-path (str/replace (u/expand uri) #"^file://" "")))

(defn- plugin-source
  "The source as `claude plugin marketplace add` takes it: owner/repo, a URL,
   or a path. The CLI rejects `file://`, so a local URI becomes its path."
  [p]
  (cond (:repo p) (:repo p)
        (local-uri? (:uri p)) (uri-path (:uri p))
        :else (:uri p)))

(defn- plugin-key [p]
  (str (:plugin p) "@" (:marketplace p)))

(defn- plugin-marketplace
  "The `extraKnownMarketplaces` entry the CLI itself writes for this source:
   github for owner/repo, directory for a local path, url for a hosted
   marketplace.json, and git for any other remote."
  [p]
  (let [uri (:uri p)]
    {:source (cond (:repo p) {:source "github" :repo (:repo p)}
                   (local-uri? uri) {:source "directory" :path (uri-path uri)}
                   (re-find #"(?i)\.json$" uri) {:source "url" :url uri}
                   :else {:source "git" :url uri})}))

(def installed-plugins-file (str home "/plugins/installed_plugins.json"))

(defn- installed?
  "Whether the CLI's own install record holds `key` at this scope — for a
   project or local scope, at this project. Settings alone do not install a
   plugin; an enabled entry with no install is drift."
  [key scope project]
  (let [rows (get-in (u/read-json installed-plugins-file) [:plugins (keyword key)])
        want (plugin-scope scope)
        canon #(str (fs/canonicalize (u/abs-path %)))
        here (some-> project :path canon)]
    (boolean
     (some (fn [{s :scope p :projectPath}]
             (and (= want s)
                  (or (= "user" want)
                      (= here (some-> p canon)))))
           rows))))

(defn- not-installed? [{:keys [exit err out]}]
  (and (not (zero? exit)) (re-find #"is not installed" (str err " " out))))

(defn- plugin-entry [id p proj scope]
  {:id id :plugin p :project proj :scope scope
   :file (plugin-settings-file proj scope)
   :cli-scope (plugin-scope scope)
   :key (plugin-key p)})

(defn- plugin-install-op [{:keys [id plugin project scope file cli-scope key]}]
  (let [current (u/read-json file)
        marketplaces (or (:extraKnownMarketplaces current) {})
        enabled (or (:enabledPlugins current) {})
        mname (:marketplace plugin)
        want-marketplace (plugin-marketplace plugin)
        before-m (get marketplaces (keyword mname))
        before-e (get enabled (keyword key))
        desired-m (not= (u/norm before-m) (u/norm want-marketplace))
        desired-e (not= true before-e)
        missing (not (installed? key scope project))
        source (plugin-source plugin)
        cwd (:path project)
        argv-add ["claude" "plugin" "marketplace" "add" "--scope" cli-scope source]
        ;; the marketplace is registered by argv-add, so the install names it
        ;; once, in the key; the CLI rejects plugin@marketplace with --marketplace
        argv-install ["claude" "plugin" "install" "--scope" cli-scope "-y" key]
        run (fn [argv] (ok! (if cwd (claude-sh-in cwd argv) (claude-sh argv)) argv))]
    (when (or desired-m desired-e missing)
      (plan/op
       {:action (if (nil? current) :create :update)
        :tool tool :kind :plugins :id id :project (some-> (:id project) keyword)
        :project-path (some-> project :path) :target file
        :path ["enabledPlugins" key] :cmds [argv-add argv-install]
        :summary (str "Claude marketplace " mname "; enable " key
                      (when (and missing (not desired-m) (not desired-e))
                        " (enabled in settings but not installed)"))
        :diffs (cond-> []
                 desired-m (conj {:key :extraKnownMarketplaces :before before-m :after want-marketplace})
                 desired-e (conj {:key :enabledPlugins :before before-e :after true})
                 missing (conj {:key :installed :before false :after true}))
        :exec! (fn []
                 (run argv-add)
                 (run argv-install)
                 ;; Keep the JSON deterministic even when the CLI is stubbed,
                 ;; unavailable in a test harness, or writes a different
                 ;; ordering than the settings file expects.
                 (u/backup! file)
                 (u/write-json! file
                                (-> (or (u/read-json file) {})
                                    (assoc-in [:extraKnownMarketplaces (keyword mname)] want-marketplace)
                                    (assoc-in [:enabledPlugins (keyword key)] true))))}))))

(defn- plugin-uninstall-op [{:keys [id plugin project scope file cli-scope key]}]
  (let [current (u/read-json file)
        before (get-in current [:enabledPlugins (keyword key)])
        cwd (:path project)
        argv ["claude" "plugin" "uninstall" "--scope" cli-scope "-y" key]]
    (when (some? before)
      (plan/op
       {:action :delete :tool tool :kind :plugins :id id :project (some-> (:id project) keyword)
        :project-path (some-> project :path) :target file :path ["enabledPlugins" key]
        :cmds [argv] :summary "removed from agents.edn" :risk :medium
        :diffs [{:key :enabledPlugins :before before :after nil}]
        :exec! (fn []
                 ;; a plugin enabled in settings but never installed is
                 ;; already gone as far as the CLI is concerned
                 (let [r (if cwd (claude-sh-in cwd argv) (claude-sh argv))]
                   (when-not (not-installed? r) (ok! r argv)))
                 (u/backup! file)
                 (let [s (or (u/read-json file) {})
                       eps (dissoc (or (:enabledPlugins s) {}) (keyword key))]
                   (u/write-json! file (assoc s :enabledPlugins eps))))}))))

(defn plugin-ops
  "Provision Claude marketplace registrations and enabled plugins. The CLI is
   used for its own installation lifecycle; the settings write makes previews,
   tests, and ownership deterministic and preserves unrelated settings."
  [cfg st]
  (let [global (for [[id p] (:plugins cfg)
                     :when (and (= :global (:scope p)) (contains? (:tools p) tool))]
                 (plugin-entry id p nil :global))
        project (mapcat
                 (fn [[pid proj]]
                   (when (contains? (:for-tools proj) tool)
                     (for [id (:plugins proj)
                           :let [p (get-in cfg [:plugins id])]
                           :when (and p (contains? (:tools p) tool))]
                       (plugin-entry (keyword (str (name pid) "/" (name id)))
                                     p (assoc proj :id pid) (:scope p)))))
                 (:projects cfg))
        desired (concat global project)
        desired-ids (set (map :id desired))
        managed (for [sid (state/managed-ids st tool :plugins)
                      :let [id (keyword sid)
                            e (state/entry st tool :plugins id)]
                      :when (not (contains? desired-ids id))]
                  (plugin-entry id
                                {:plugin (or (:plugin e) (name id))
                                 :marketplace (or (:marketplace e) (name id))}
                                (when-let [p (:project-path e)] {:path p :id (some-> id namespace keyword)})
                                (or (:scope e) :global)))]
    (concat (keep plugin-install-op desired)
            (keep plugin-uninstall-op managed))))

(def ^:private transport-keys
  "The keys that say how Claude Code reaches a server, for any transport."
  [:type :command :args :url :env :headers])

(defn- str-keys
  "JSON reads a key back as a keyword and a resolved value carries a string:
   merged as they are, one name would be written twice."
  [m]
  (into {} (map (fn [[k v]] [(u/key-str k) v])) m))

(defn- mcp-json-entry
  "The entry as Claude Code reads it, in `.mcp.json` or a project's local
   scope. On the same transport the declared fields are merged onto what is
   there, so an env var, header or unmodelled key the user added survives:
   neither file is agentctl's alone. The declared type, command, args and url
   are the server itself and replace what was there.

   A transport change replaces every transport key instead. Merged, a stdio
   server's command, args and env would stay beside the new url, and the
   entry would describe two servers at once."
  [m cur env headers]
  (let [same? (and cur (= (name (:transport m)) (entry-type cur)))
        kept (if same? cur (apply dissoc cur transport-keys))]
    (merge (dissoc kept :type :command :args :url)
           (:extra m)
           (u/prune-nils
            {:type (name (:transport m))
             :command (:command m)
             :args (not-empty (vec (:args m)))
             :url (:url m)
             :env (not-empty (merge (str-keys (:env kept)) (:values env)))
             :headers (not-empty (merge (str-keys (:headers kept)) (:values headers)))}))))

(defn- project-mcp-target
  "Where a project's server is written. Local scope — the default — is the
   project's own entry in `~/.claude.json`, which is Claude Code's local scope
   and the one place a token can sit without being committed. `:scope :project`
   opts into the shared `.mcp.json` instead."
  [proj m mid]
  (if (= :project (:scope m))
    {:file (project-mcp-file proj)
     :path [:mcpServers (keyword (u/kw->str mid))]
     :label ".mcp.json"
     :shared? true}
    {:file runtime-file
     :path [:projects (keyword (:path proj)) :mcpServers (keyword (u/kw->str mid))]
     :label "~/.claude.json (local scope)"
     :shared? false}))

(defn- declared-secret-keys
  "Names in a declared env or header map whose value is a credential."
  [refs-map]
  (common/secret-keys refs-map (into {} (map (fn [[k r]] [k (if (refs/ref? r) (:ref/raw r) r)])) refs-map)))

(defn- mask-entry-diffs
  "An MCP entry's diff as a plan may print it. The entry carries resolved
   values, and rendering masks only a credential-shaped name or value, so a
   secret under `DOCS_KEY` would print in clear — in the plan and in --json.
   Values under a declared secret name are masked, and so is every value the
   change removes: nothing needs to read a value that is going away."
  [op env-ks header-ks]
  (letfn [(mask [d k ks]
            (let [b (get (:before d) k)
                  a (get (:after d) k)
                  gone (remove (set (map u/key-str (keys a))) (map u/key-str (keys b)))
                  [b' a'] (common/mask-secrets b a (into ks gone))]
              (cond-> d
                (map? b) (assoc-in [:before k] b')
                (map? a) (assoc-in [:after k] a'))))]
    (cond-> op
      (seq (:diffs op))
      (update :diffs (partial mapv #(-> % (mask :env env-ks) (mask :headers header-ks)))))))

(defn- project-mcp-writes [cfg id proj]
  (for [[mid m] (common/project-mcps cfg proj tool)
        :let [{:keys [file path shared?]} (project-mcp-target proj m mid)
              oid (keyword (str (name id) "/" (name mid)))
              current (get-in (u/read-json file) path)
              {:keys [env headers issues]} (resolve-mcp m)
              secret? (common/holds-secret? (merge (:env m) (:headers m)))
              op (if (seq issues)
                   (common/unresolved-op {:tool tool :kind :mcps :project id :id oid :target file} issues)
                   (some-> (plan/json-set-op
                            {:tool tool :kind :mcps :project id
                             :id oid
                             :file file
                             :path path
                             :value (mcp-json-entry m current env headers)
                             :risk (if secret? :secret :low)
                             :summary (common/mcp-summary m)
                             :note (when (and secret? shared?)
                                     "⚠ secret value written into the project — .mcp.json is usually tracked")})
                           (mask-entry-diffs (declared-secret-keys (:env m))
                                             (declared-secret-keys (:headers m)))))]
        :when op]
    op))

(defn- project-mcp-ops [cfg id proj]
  (let [tracked (or (:mcpServers (u/read-json (project-mcp-file proj))) {})]
    (concat
     ;; a copy left behind in .mcp.json still ships the server — and its token —
     ;; to everyone who clones the repo. Not ours to delete: agentctl never
     ;; wrote it, and the file may hold servers it knows nothing about
     (for [[mid m] (common/project-mcps cfg proj tool)
           :when (and (not= :project (:scope m))
                      (contains? tracked (keyword (u/kw->str mid))))]
       (plan/op {:action :noop :warn true :tool tool :kind :mcps :project id
                 :id (keyword (str (name id) "/" (name mid)))
                 :summary (str "⚠ also declared in " (u/tilde (project-mcp-file proj))
                               " — local scope owns it now; remove it there"
                               (when (common/holds-secret?
                                      (:env (get tracked (keyword (u/kw->str mid)))))
                                 ", the copy carries a credential"))}))
     (project-mcp-writes cfg id proj))))

(defn- project-mcp-prune-ops
  "A project's server dropped from agents.edn. The user-wide prune loop skips a
   namespaced id on purpose — it would `claude mcp remove --scope user` the
   wrong server — so the project's own entry has to be cleaned up here, or
   moving a server out of a project would leak it forever.

   Both scopes are checked because the entry may have been written under either
   one, and a scope flip is indistinguishable from a removal in the manifest."
  [cfg st]
  (let [desired (into #{} (for [[pid proj] (:projects cfg)
                                :when (contains? (:for-tools proj) tool)
                                [mid _] (common/project-mcps cfg proj tool)]
                            (keyword (name pid) (u/kw->str mid))))
        managed (into #{} (map keyword) (state/managed-ids st tool :mcps))]
    (for [id managed
          :let [old (state/entry st tool :mcps id)
                wanted-scope (get-in cfg [:mcps (keyword (name id)) :scope])]
          :when (and (namespace id) (or (not (contains? desired id))
                                       (and (:scope old) (not= (:scope old) wanted-scope))))
          :let [proj (or (get-in cfg [:projects (keyword (namespace id))])
                         (when (:project-path old) {:path (:project-path old)}))]
          ;; Version 2 state records the path even after the project declaration
          ;; disappears. Version 1 cannot recover a path it never stored.
          :when proj
          [file path] (let [local [runtime-file [:projects (keyword (:path proj)) :mcpServers (keyword (name id))]]
                            repo [(project-mcp-file proj) [:mcpServers (keyword (name id))]]]
                        (case (:scope old) :local [local] :project [repo] [local repo]))
          :let [op (plan/json-unset-op {:tool tool :kind :mcps :id id
                                        :project (keyword (namespace id))
                                        :file file :path path
                                        :summary "removed from agents.edn"})]
          :when op]
      ;; the entry carries resolved secrets, and the declaration that named
      ;; them is gone: print none of its env or header values
      (update op :diffs (partial mapv (fn [d] (update d :before #(cond-> %
                                                                    (:env %) (update :env common/mask-all)
                                                                    (:headers %) (update :headers common/mask-all)))))))))

(defn project-local-settings-file [proj] (str (:path proj) "/.claude/settings.local.json"))

(defn- mcp-approval-op
  "Approve the project's `.mcp.json` servers, so Claude Code starts them
   without asking. Only those need approving: a local-scope server is already
   the user's own. Claude Code honours `enabledMcpjsonServers` in the
   project's `.claude/settings.local.json`; the same key under the project in
   `~/.claude.json` is migrated into that file and deleted on the next
   `claude` run, so written there it came back as drift after every run.

   The list is shared with the user and with Claude Code's own approval
   prompt, so declared servers are added to it and nothing is removed. It is
   an MCP op, not a settings one: it belongs with the servers it approves,
   and stays out of the settings ownership pass, which records keys a
   declared setting owns.

   A server held back on an unresolved secret is not approved: nothing was
   written to `.mcp.json` for it, and an approval would start whatever entry
   is there under that name."
  [cfg id proj]
  (when-let [shared (not-empty (keys (filter (fn [[_ m]] (and (= :project (:scope m))
                                                              (empty? (:issues (resolve-mcp m)))))
                                             (common/project-mcps cfg proj tool))))]
    (plan/json-union-op {:tool tool :kind :mcps :project id
                         :id (keyword (name id) "enabledMcpjsonServers")
                         :file (project-local-settings-file proj)
                         :path [:enabledMcpjsonServers]
                         :values (mapv u/kw->str (sort shared))
                         :risk :medium
                         :summary "approve the project's .mcp.json servers"})))

(defn- project-ops-for [cfg id proj]
  (let [pfile (project-settings-file proj)
        tool-settings (get-in proj [:tools tool])
        [kvs unsupported] (common/map-settings tool-settings setting-keys)]
    (concat
     ;; hooks have no project scope in the DSL today — `:hooks` under a
     ;; project's `:executors :claude` would otherwise vanish with no trace;
     ;; say so instead of silently dropping it
     (for [k unsupported :when (not (and (:entity-dsl? cfg) (= :hooks k)))]
       (plan/op {:action :noop :warn true :tool tool :kind :projects :project id
                 :id (keyword (name id) (name k))
                 :summary (str (if (= :hooks k)
                                 "hooks have no project scope — declare under the global :executors :claude :hooks"
                                 (str "unsupported setting " (name k) " for claude — ignored")))}))
     (keep identity
           [(when (or (seq kvs) (seq (:permissions proj)))
              (plan/json-set-op {:tool tool :kind :projects :project id
                                 :id (keyword (str (name id) "/$schema"))
                                 :file pfile :path [:$schema] :value settings-schema-url
                                 :summary (str (u/tilde pfile) " $schema")}))])
     ;; per-project settings file: model / output style / permissions
     (keep identity
           (concat
            (for [[k v] kvs
                  :let [base {:tool tool :kind :projects :project id
                              :id (keyword (str (name id) "/" (name k)))
                              :file pfile :path [k]
                              :summary (str (u/tilde pfile) " " (name k))}]]
              (if (= :env k)
                (env-setting-op base v (str "⚠ secret value written into the project — "
                                            ".claude/settings.json is usually tracked"))
                (plan/json-set-op (assoc base :value v))))
            ;; one op per declared bucket: writing the whole :permissions map
            ;; would drop defaultMode / additionalDirectories the CLI put there
            (for [[bucket rules] (:permissions proj)]
              (plan/json-set-op {:tool tool :kind :projects :project id
                                 :id (keyword (str (name id) "/permissions." (u/kw->str bucket)))
                                 :file pfile :path [:permissions bucket] :value rules
                                 :compare-as (when (coll? rules) :set)
                                 :summary (str (u/tilde pfile) " permissions." (u/kw->str bucket))}))))
     ;; trust lives in the runtime blob
     (keep identity
           [(when (some? (:trusted proj))
              (plan/json-set-op {:tool tool :kind :projects :project id
                                 :id (keyword (str (name id) "/trust"))
                                 :file runtime-file
                                 :path [:projects (keyword (:path proj)) :hasTrustDialogAccepted]
                                 :value (boolean (:trusted proj))
                                 :risk :medium
                                 :summary "trust project in ~/.claude.json"}))
            (mcp-approval-op cfg id proj)]))))

(defn project-skills-dir [proj] (str (:path proj) "/.claude/skills"))

(defn- project-skill-ops
  "Installing a project's skills is the `skills` CLI's job (`skills-cli`);
   what is left here is dropping a project copy of a user-wide skill."
  [cfg id proj _st]
  (sources/global-project-unlink-ops cfg tool id (project-skills-dir proj) skills-dir))

(defn project-ops [cfg st]
  (concat
   (mapcat (fn [[id proj]]
             (when (contains? (:for-tools proj) tool)
               (concat (project-ops-for cfg id proj)
                       (project-mcp-ops cfg id proj)
                       (project-skill-ops cfg id proj st))))
           (:projects cfg))
   (project-mcp-prune-ops cfg st)))

;; ---------------------------------------------------------------- entry

(defn plan [cfg st]
  (concat (settings-ops cfg)
          (hook-ops cfg st)
          (mcp-ops cfg st)
          (plugin-ops cfg st)
          (skill-ops cfg st)
          (memory-ops cfg)
          (project-ops cfg st)))

(defn present? [] (some? (u/which "claude")))
