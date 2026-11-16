(ns agentctl.adapters.claude
  "Claude Code adapter.

   settings.json is agentctl-editable (plain config). ~/.claude.json is a large
   runtime state blob owned by the CLI: it is read for current state, and only
   ever mutated through `claude mcp` or narrowly-scoped project keys."
  (:require [agentctl.adapters.common :as common]
            [agentctl.adapters.claude-plugins :as cp]
            [agentctl.adapters.claude-sources :as cs]
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

(defn- value-arg
  "An argv element carrying an env or header value (`prefix` is `K=` or
   `k: `), and what a message may show of it: the value masked where it
   sits, never searched for elsewhere in the text."
  [prefix v]
  {:arg (str prefix v) :shown (str prefix (refs/mask v))})

(defn- arg-text [a] (if (map? a) (:arg a) a))
(defn- arg-shown [a] (if (map? a) (:shown a) a))

(defn- ok!
  "Turn a non-zero CLI exit into a real failure, so apply! reports the op as
   failed and claims no ownership of what the CLI never wrote — a server it
   refused to add, a plugin it never installed.

   The message names the command, and `claude mcp add` carries its env and
   header values on the command line, where the CLI may echo them back. The
   message is printed and served to the GUI, so a value in the command
   (`value-arg`) is shown masked where it sits, and in the CLI's own output
   each of `secrets` is masked wherever it appears. Only secrets are looked
   for there: masking every value would turn a plain `s` into a row of
   asterisks inside every word that holds one."
  ([result argv] (ok! result argv nil))
  ([{:keys [exit err out]} argv secrets]
   (when-not (zero? exit)
     (let [shown (mapv arg-shown argv)]
       (throw (ex-info (str (str/join " " shown) " failed: "
                            (hide (first (remove str/blank? [err out])) secrets))
                       {:exit exit :argv shown}))))
   true))

(defn- claude-ok!
  "Run the claude CLI and fail on a non-zero exit."
  ([argv] (claude-ok! argv nil))
  ([argv secrets] (ok! (claude-sh (mapv arg-text argv)) argv secrets)))

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
     (mapcat (fn [[id decl]]
               (let [path (hook-path decl)
                     entry (state/entry st tool :hooks id)]
                 (plan/json-array-place-ops
                  {:tool tool :kind :hooks :id id
                   :file settings-file :path path :old (:value entry) :old-path (:path entry)
                   :value (hook-group decl)
                   :report-converged? true
                   :summary (str "settings.json hooks." (name (last path))
                                 (when-let [m (:matcher decl)] (str " [" m "]")))})))
             declared)
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

(defn- str-keys
  "JSON reads a key back as a keyword and a resolved value carries a string:
   merged as they are, one name would be written twice."
  [m]
  (into {} (map (fn [[k v]] [(u/key-str k) v])) m))

(defn declared-names
  "The env and header names a server declares, kept in the manifest beside
   it. A name agentctl declared and the declaration has since dropped is
   agentctl's to remove; a name it never declared was added by hand, and is
   kept."
  [m]
  (common/declared-names m))

(defn- dropped-names
  "Names under `k` (`:env-names`, `:header-names`) that the manifest entry
   `old` says agentctl declared, and `m` no longer does. An entry recorded
   before names were kept says nothing, so nothing is dropped."
  [old m k]
  (let [now (set (get (declared-names m) k))]
    (into #{} (remove now) (get old k))))

(defn- entry-type
  "The transport an entry is written for. Claude Code infers it when `type`
   is absent, and so does this."
  [entry]
  (or (:type entry) (if (:url entry) "http" "stdio")))

(defn- desired-shape
  "What `claude mcp add` will write: env for a stdio server, headers for an
   http one — the other is never passed, so counting it would be drift that
   no apply can clear. `carried` are the current values kept beside the
   declared ones (`carried-values`), so their names are part of the entry."
  [m carried]
  (let [http? (= :http (:transport m))
        names (fn [declared] (key-names (merge carried (str-keys declared))))]
    (u/prune-nils
     {:type (name (:transport m))
      :command (:command m)
      :args (not-empty (vec (:args m)))
      :url (:url m)
      :env (when-not http? (names (:env m)))
      :headers (when http? (names (:headers m)))})))

(defn- carried-values
  "The env (stdio) or header (http) values in the current entry that the
   declaration does not name. `claude mcp add` cannot edit an entry, so an
   update is a remove and an add, and a value left off the add is gone: an
   `Authorization` header or an env var added by hand would be wiped by an
   unrelated change. They are passed to the add as they are, except a name
   agentctl declared and the declaration has since dropped."
  [m cur old]
  (let [http? (= :http (:transport m))
        declared (if http? (:headers m) (:env m))]
    (apply dissoc (str-keys (if http? (:headers cur) (:env cur)))
           (concat (map u/key-str (keys declared))
                   (dropped-names old m (if http? :header-names :env-names))))))

(defn- current-shape [entry]
  (u/prune-nils
   {:type (entry-type entry)
    :command (:command entry)
    :args (not-empty (vec (:args entry)))
    :url (:url entry)
    :env (key-names (:env entry))
    :headers (key-names (:headers entry))}))

(declare declared-secret-keys)

(defn- resolve-mcp
  "A server's env and headers with every ref resolved, and the refs that did
   not resolve: a server holding one is not written, and not approved."
  [m]
  (let [env (common/resolve-env (:env m))
        headers (common/resolve-env (:headers m))]
    {:env env
     :headers headers
     :issues (concat (common/issues-in "env" env) (common/issues-in "headers" headers) (:arg-issues m))}))

(defn- restore-user-mcp!
  "Put back a user-wide server's entry as it was before the update removed
   it, after `claude mcp add` refused the new one: otherwise a failed update
   would uninstall the server. Says what happened, for the failure message."
  [nm prev]
  (try
    (u/write-json! runtime-file (assoc-in (plan/read-json-strict runtime-file) [:mcpServers (keyword nm)] prev))
    "the previous definition was restored"
    (catch Exception e
      (str "restoring the previous definition failed too (" (ex-message e) "); ~/.claude.json is backed up in "
           (u/tilde (u/backup-dir))))))

(defn mcp-ops [cfg st]
  (let [desired (common/for-tool-resources (common/global-mcps (:mcps cfg) tool) tool)
        current (current-mcps)
        managed (into #{} (map keyword) (state/managed-ids st tool :mcps))]
    (concat
     (for [[id m] desired
           :let [nm (u/kw->str id)
                 cur (get current (keyword nm))
                 carried (carried-values m cur (state/entry st tool :mcps id))
                 ;; the declared names plus the ones carried over: drift is a
                 ;; declared name the entry lacks, or a dropped one it still has
                 want (desired-shape m carried)
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
                   :note (when (common/holds-secret? (common/mcp-secrets m))
                           "⚠ secret value stored in ~/.claude.json")
                   :diffs (plan/field-diffs (or have {}) want)
                   :risk (if (common/holds-secret? (common/mcp-secrets m)) :secret :low)
                   :exec! (fn []
                            (let [prev (when cur (get (current-mcps) (keyword nm)))]
                              (when prev
                                ;; the remove drops what the add may fail to put back
                                (u/backup! runtime-file)
                                (claude-ok! ["claude" "mcp" "remove" "--scope" "user" nm]))
                              (try
                            (claude-ok!
                             (vec (if (= :http (:transport m))
                                    (concat ["claude" "mcp" "add" "--scope" "user"
                                             "--transport" "http" nm (:url m)]
                                            (mapcat (fn [[k v]] ["--header" (value-arg (str k ": ") v)])
                                                    (merge carried (:values headers))))
                                    (concat ["claude" "mcp" "add" "--scope" "user" nm]
                                            (mapcat (fn [[k v]] ["-e" (value-arg (str k "=") v)])
                                                    (merge carried (:values env)))
                                            ["--"] [(:command m)] (:args m))))
                             ;; what the CLI may echo back: the values from a secret
                             ;; ref or shaped like one, and every value carried over,
                             ;; whose origin nothing records
                             (concat (vals (select-keys (:values env) (declared-secret-keys (:env m))))
                                     (vals (select-keys (:values headers) (declared-secret-keys (:headers m))))
                                     (vals carried)))
                                (catch clojure.lang.ExceptionInfo e
                                  (throw (if prev
                                           (ex-info (str (ex-message e) " — " (restore-user-mcp! nm prev))
                                                    (ex-data e))
                                           e))))))})))
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

(defn- with-ref
  "The CLI reads a git ref after `#`: `owner/repo#ref`, `<git-url>#ref`."
  [source ref]
  (if ref (str source "#" ref) source))

(defn- plugin-source
  "The source as `claude plugin marketplace add` takes it: owner/repo, a URL,
   or a path, a git one with its ref. The CLI rejects `file://`, so a local
   URI becomes its path."
  [p]
  (cond (:repo p) (with-ref (:repo p) (:ref p))
        (local-uri? (:uri p)) (uri-path (:uri p))
        :else (with-ref (:uri p) (:ref p))))

(defn- plugin-key [p]
  (str (:plugin p) "@" (:marketplace p)))

(defn- plugin-marketplace
  "The `extraKnownMarketplaces` source the CLI itself writes for this row:
   github for owner/repo, directory for a local path, and for a remote URL
   what `cs/remote` says the CLI reads it as. Git sources carry the ref."
  [p]
  (let [uri (:uri p)]
    {:source (u/prune-nils
              (cond (:repo p) {:source "github" :repo (:repo p) :ref (:ref p)}
                    (local-uri? uri) {:source "directory" :path (uri-path uri)}
                    :else (let [r (cs/remote uri)]
                            (cond-> r (= "git" (:source r)) (assoc :ref (:ref p))))))}))

;; Whether the CLI's own install record holds a plugin at a scope is
;; `cp/install-record`: settings alone do not install a plugin, and an enabled
;; entry with no install is drift.
(def installed-plugins-file cp/installed-file)

(defn- not-installed? [{:keys [exit err out]}]
  (and (not (zero? exit)) (re-find #"is not installed" (str err " " out))))

(defn- plugin-entry [id p proj scope]
  {:id id :plugin p :project proj :scope scope
   :file (plugin-settings-file proj scope)
   :cli-scope (plugin-scope scope)
   :key (plugin-key p)})

(defn- plugin-install-op [{:keys [id plugin project scope file cli-scope key remote rows-clash]}]
  (let [current (u/read-json file)
        marketplaces (or (:extraKnownMarketplaces current) {})
        enabled (or (:enabledPlugins current) {})
        mname (:marketplace plugin)
        before-m (get marketplaces (keyword mname))
        ;; keys the row does not declare (`autoUpdate`, …) are the user's and stay
        want-marketplace (merge before-m (plugin-marketplace plugin))
        before-e (get enabled (keyword key))
        desired-m (not= (u/norm (:source before-m)) (u/norm (:source want-marketplace)))
        desired-e (not= true before-e)
        rec (cp/install-record key cli-scope (:path project))
        missing (nil? rec)
        ;; settings already name this marketplace, from another source or ref
        ;; (a URL spelled with or without `.git` is the same remote)
        switched (and before-m (not (cs/same-source? (:source before-m) (:source want-marketplace))))
        ;; a source switch re-registers the marketplace, which then offers what
        ;; it offers: the version on disk now says nothing about it
        want (:version plugin)
        offer (when (and want (not desired-m)) (cp/offered mname (:plugin plugin)))
        ;; only `:latest` asks the network, and only of an installed plugin
        moved (when (and (= :latest want) rec offer) (remote mname))
        vdiff (when-not desired-m (cp/version-diff want rec offer moved))
        ;; Claude adds a marketplace to a project only from the source the
        ;; user settings name for it, spelled the same way
        user-source (when (not= "user" cli-scope)
                      (get-in (u/read-json settings-file) [:extraKnownMarketplaces (keyword mname) :source]))
        clash (and user-source (not= (u/norm user-source) (u/norm (:source want-marketplace))))
        source (plugin-source plugin)
        cwd (:path project)
        argv-add ["claude" "plugin" "marketplace" "add" "--scope" cli-scope source]
        ;; the marketplace is registered by argv-add, so the install names it
        ;; once, in the key; the CLI rejects plugin@marketplace with --marketplace
        steps (cp/steps {:key key :cli-scope cli-scope :installed? (not missing)
                         :switched? (and switched (not missing)) :behind? (some? vdiff)})
        run (fn [argv] (ok! (if cwd (claude-sh-in cwd argv) (claude-sh argv)) argv))]
    (when (or desired-m desired-e missing vdiff)
      (plan/op
       {:action (if (nil? current) :create :update)
        :tool tool :kind :plugins :id id :project (some-> (:id project) keyword)
        :project-path (some-> project :path) :target file
        :path ["enabledPlugins" key]
        :cmds (vec (concat [argv-add] (when want [(cp/refresh-cmd mname)]) steps))
        :summary (str "Claude marketplace " mname "; enable " key
                      (when (and missing (not desired-m) (not desired-e))
                        " (enabled in settings but not installed)"))
        :note (or (when rows-clash
                    (str "⚠ agents.edn declares marketplace " mname " from more than one source;"
                         " Claude keeps one source per name, so apply! fails for all but one"))
                  (when clash
                    (str "⚠ " (u/tilde settings-file) " declares marketplace " mname " from "
                         (pr-str user-source) "; Claude adds it to a project only from that source,"
                         " so apply! fails until the two agree"))
                  (when (and switched (not missing))
                    (str "marketplace source changed: " key
                         " is reinstalled, Claude would keep its cached copy at an unchanged version"))
                  (cp/pin-note want mname offer))
        :diffs (cond-> []
                 desired-m (conj {:key :extraKnownMarketplaces :before before-m :after want-marketplace})
                 desired-e (conj {:key :enabledPlugins :before before-e :after true})
                 missing (conj {:key :installed :before false :after true})
                 (and missing want) (conj {:key :version :before nil
                                           :after (or (cp/wanted-version want offer nil) "latest")})
                 vdiff (conj vdiff))
        :exec! (fn []
                 (plan/read-json-strict file)
                 (let [before (u/slurp-safe file)]
                   (try
                     ;; Claude refuses to re-point a marketplace whose
                     ;; user-settings entry names another source, so the
                     ;; entry is written first
                     (when desired-m
                       (u/backup! file)
                       (u/write-json! file (update-in (plan/read-json-strict file)
                                                      [:extraKnownMarketplaces (keyword mname)]
                                                      merge want-marketplace)))
                     (run argv-add)
                     (cp/converge! {:run run :key key :cli-scope cli-scope :project-path cwd
                                    :mname mname :plugin (:plugin plugin) :want want
                                    :switched? (boolean (and switched (not missing)))})
                     ;; a failed step leaves the settings file as it was
                     (catch Exception e
                       (if before (u/write-text! file before) (fs/delete-if-exists file))
                       (throw e))))
                 ;; Keep the JSON deterministic even when the CLI is stubbed,
                 ;; unavailable in a test harness, or writes a different
                 ;; ordering than the settings file expects.
                 (u/backup! file)
                 (u/write-json! file
                                (-> (plan/read-json-strict file)
                                    ;; `marketplace add` rewrites the entry whole, dropping
                                    ;; keys such as autoUpdate: put back what was there
                                    (update-in [:extraKnownMarketplaces (keyword mname)] merge
                                               want-marketplace)
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
                 (plan/read-json-strict file)
                 ;; a plugin enabled in settings but never installed is
                 ;; already gone as far as the CLI is concerned
                 (let [r (if cwd (claude-sh-in cwd argv) (claude-sh argv))]
                   (when-not (not-installed? r) (ok! r argv)))
                 (u/backup! file)
                 (let [s (plan/read-json-strict file)
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
        clashing (set (for [[m es] (group-by (comp :marketplace :plugin) desired)
                            :when (next (distinct (map #(:source (plugin-marketplace (:plugin %))) es)))]
                        m))
        desired (map #(cond-> % (clashing (get-in % [:plugin :marketplace])) (assoc :rows-clash true)) desired)
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
    (concat (let [remote (memoize cp/remote-revision)]
              ;; a plugin's build follows its install, which may move it
              (mapcat (fn [e] (let [o (plugin-install-op (assoc e :remote remote))]
                                (remove nil? [o (cp/build-op e (some? o))])))
                      desired))
            (keep plugin-uninstall-op managed))))

(def ^:private transport-keys
  "The keys that say how Claude Code reaches a server, for any transport."
  [:type :command :args :url :env :headers])

(defn- mcp-json-entry
  "The entry as Claude Code reads it, in `.mcp.json` or a project's local
   scope. On the same transport the declared fields are merged onto what is
   there, so an env var, header or unmodelled key the user added survives:
   neither file is agentctl's alone. The declared type, command, args and url
   are the server itself and replace what was there.

   A transport change replaces every transport key instead. Merged, a stdio
   server's command, args and env would stay beside the new url, and the
   entry would describe two servers at once.

   An env var or header agentctl declared and the declaration has since
   dropped (`old`, the server's manifest entry) is removed: merged, a
   dropped secret would stay in the file with no drift to show it."
  [m cur env headers old]
  (let [same? (and cur (= (name (:transport m)) (entry-type cur)))
        kept (if same? cur (apply dissoc cur transport-keys))
        keep-map (fn [cur-map k] (apply dissoc (str-keys cur-map) (dropped-names old m k)))]
    (merge (dissoc kept :type :command :args :url :env :headers)
           (:extra m)
           (u/prune-nils
            {:type (name (:transport m))
             :command (:command m)
             :args (not-empty (vec (:args m)))
             :url (:url m)
             :env (not-empty (merge (keep-map (:env kept) :env-names) (:values env)))
             :headers (not-empty (merge (keep-map (:headers kept) :header-names) (:values headers)))}))))

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
   change replaces or removes (`common/mask-secrets`)."
  [op env-ks header-ks]
  (letfn [(mask [d k ks]
            (let [b (get (:before d) k)
                  a (get (:after d) k)
                  [b' a'] (common/mask-secrets b a ks)]
              (cond-> d
                (map? b) (assoc-in [:before k] b')
                (map? a) (assoc-in [:after k] a'))))]
    (cond-> op
      (seq (:diffs op))
      (update :diffs (partial mapv #(-> % (mask :env env-ks) (mask :headers header-ks)))))))

(defn- project-mcp-writes [cfg st id proj]
  (for [[mid m] (common/project-mcps cfg proj tool)
        :let [{:keys [file path shared?]} (project-mcp-target proj m mid)
              oid (keyword (str (name id) "/" (name mid)))
              current (get-in (u/read-json file) path)
              {:keys [env headers issues]} (resolve-mcp m)
              secret? (common/holds-secret? (common/mcp-secrets m))
              op (if (seq issues)
                   (common/unresolved-op {:tool tool :kind :mcps :project id :id oid :target file} issues)
                   (some-> (plan/json-set-op
                            {:tool tool :kind :mcps :project id
                             :id oid
                             :file file
                             :path path
                             :value (mcp-json-entry m current env headers (state/entry st tool :mcps oid))
                             :risk (if secret? :secret :low)
                             :summary (common/mcp-summary m)
                             :note (when (and secret? shared?)
                                     "⚠ secret value written into the project — .mcp.json is usually tracked")})
                           (mask-entry-diffs (declared-secret-keys (:env m))
                                             (declared-secret-keys (:headers m)))))]
        :when op]
    op))

(defn- project-mcp-ops [cfg st id proj]
  (let [tracked (or (:mcpServers (u/read-json (project-mcp-file proj))) {})]
    (concat
     ;; a copy left behind in .mcp.json still ships the server — and its token —
     ;; to everyone who clones the repo. Not ours to delete: agentctl never
     ;; wrote it, and the file may hold servers it knows nothing about. A copy
     ;; agentctl did write there is pruned by this same plan
     ;; (`project-mcp-prune-ops`), and needs no warning
     (for [[mid m] (common/project-mcps cfg proj tool)
           :let [oid (keyword (str (name id) "/" (name mid)))]
           :when (and (not= :project (:scope m))
                      (contains? tracked (keyword (u/kw->str mid)))
                      (not= :project (:scope (state/entry st tool :mcps oid))))]
       (plan/op {:action :noop :warn true :tool tool :kind :mcps :project id
                 :id (keyword (str (name id) "/" (name mid)))
                 :summary (str "⚠ also declared in " (u/tilde (project-mcp-file proj))
                               " — local scope owns it now; remove it there"
                               (when (common/holds-secret?
                                      (:env (get tracked (keyword (u/kw->str mid)))))
                                 ", the copy carries a credential"))}))
     (project-mcp-writes cfg st id proj))))

(defn project-local-settings-file [proj] (str (:path proj) "/.claude/settings.local.json"))

(defn- project-mcp-prune-ops
  "A project's server dropped from agents.edn. The user-wide prune loop skips a
   namespaced id on purpose — it would `claude mcp remove --scope user` the
   wrong server — so the project's own entry has to be cleaned up here, or
   moving a server out of a project would leak it forever.

   Both scopes are checked because the entry may have been written under either
   one, and a scope flip is indistinguishable from a removal in the manifest.

   A `.mcp.json` server whose approval agentctl added (`:approved` in the
   manifest, see `mcp-approval-op`) loses it too; one the user approved
   before agentctl did keeps it."
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
          op (concat
              (for [[file path] (let [local [runtime-file [:projects (keyword (:path proj)) :mcpServers (keyword (name id))]]
                                      repo [(project-mcp-file proj) [:mcpServers (keyword (name id))]]]
                                  (case (:scope old) :local [local] :project [repo] [local repo]))
                    :let [op (plan/json-unset-op {:tool tool :kind :mcps :id id
                                                  :project (keyword (namespace id))
                                                  :file file :path path
                                                  :summary "removed from agents.edn"})]
                    :when op]
                ;; the entry carries resolved secrets, and the declaration that
                ;; named them is gone: print none of its env or header values
                (update op :diffs (partial mapv (fn [d] (update d :before #(cond-> %
                                                                              (:env %) (update :env common/mask-all)
                                                                              (:headers %) (update :headers common/mask-all)))))))
              (when (and (:approved old) (= :project (:scope old)))
                (some-> (plan/json-array-unset-op {:tool tool :kind :mcps :id id
                                                   :project (keyword (namespace id))
                                                   :file (project-local-settings-file proj)
                                                   :path [:enabledMcpjsonServers]
                                                   :old (name id)
                                                   :summary "approval agentctl added, removed with the server"})
                        vector)))]
      op)))

(defn- mcp-approval-op
  "Approve the project's `.mcp.json` servers, so Claude Code starts them
   without asking. Only those need approving: a local-scope server is already
   the user's own. Claude Code honours `enabledMcpjsonServers` in the
   project's `.claude/settings.local.json`; the same key under the project in
   `~/.claude.json` is migrated into that file and deleted on the next
   `claude` run, so written there it came back as drift after every run.

   The list is shared with the user and with Claude Code's own approval
   prompt, so declared servers are added to it and nothing else is touched.
   `:approves` names the servers this op adds: `core/sync-state!` marks them
   `:approved` in the manifest, and `project-mcp-prune-ops` takes the name
   out again when the server goes. A name already in the list was approved
   by someone else, and stays. It is an MCP op, not a settings one: it
   belongs with the servers it approves, and stays out of the settings
   ownership pass, which records keys a declared setting owns.

   A server held back on an unresolved secret is not approved: nothing was
   written to `.mcp.json` for it, and an approval would start whatever entry
   is there under that name."
  [cfg id proj]
  (when-let [shared (not-empty (keys (filter (fn [[_ m]] (and (= :project (:scope m))
                                                              (empty? (:issues (resolve-mcp m)))))
                                             (common/project-mcps cfg proj tool))))]
    (when-let [op (plan/json-union-op {:tool tool :kind :mcps :project id
                                       :id (keyword (name id) "enabledMcpjsonServers")
                                       :file (project-local-settings-file proj)
                                       :path [:enabledMcpjsonServers]
                                       :values (mapv u/kw->str (sort shared))
                                       :risk :medium
                                       :summary "approve the project's .mcp.json servers"})]
      (assoc op :approves (mapv #(keyword (name id) %) (:added op))))))

(defn- project-ops-for [cfg id proj]
  (let [pfile (project-settings-file proj)
        tool-settings (get-in proj [:tools tool])
        [kvs unsupported] (common/map-settings tool-settings setting-keys)]
    (concat
     ;; the old format gives hooks no project scope — `:hooks` under a
     ;; project's `:executors :claude` would otherwise vanish with no trace;
     ;; say so instead of silently dropping it. The entity DSL places a hook
     ;; in a project with `:in` (see `core/project-hook-ops`)
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
                       (project-mcp-ops cfg st id proj)
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
