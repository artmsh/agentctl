(ns agentctl.adapters.codex
  "Codex CLI adapter.

   MCP servers go through `codex mcp add|remove` (verified to rewrite only the
   relevant table). Everything else is TOML key surgery so codex-owned state
   ([hooks.state], [plugins.*], trust dialogs) is preserved."
  (:require [agentctl.adapters.common :as common]
            [agentctl.codex-schema :as schema]
            [agentctl.plan :as plan]
            [agentctl.refs :as refs]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.toml :as toml]
            [agentctl.util :as u]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def tool :codex)
(def home (str u/home "/.codex"))
(def config-file (str home "/config.toml"))
(def skills-dir (str home "/skills"))
(def memory-file (str home "/AGENTS.md"))
(def hooks-file (str home "/hooks.json"))

(defn codex-sh
  "Run the codex CLI against the home directory agentctl is managing. From `/`:
   codex merges the working directory's `.codex/config.toml` into what `mcp
   list` reports, and a project's servers must not read as user-wide ones."
  [& args]
  (u/sh-env-in "/" {:CODEX_HOME home} args))

(def setting-keys
  {:model "model"
   :personality "personality"
   :thinking "model_reasoning_effort"
   :reasoning-effort "model_reasoning_effort"
   :provider "model_provider"
   :approval "approvals_reviewer"
   :sandbox "default_permissions"
   :service-tier "service_tier"})

;; ---------------------------------------------------------------- current state

(defn- ok!
  "Turn a non-zero CLI exit into a real failure so apply! reports it."
  [{:keys [exit err out]} what]
  (when-not (zero? exit)
    (throw (ex-info (str what " failed: " (first (remove str/blank? [err out]))) {:exit exit})))
  true)

(defn mcp-list
  "Configured servers, keyed by name. Falls back to reading config.toml when the
   CLI refuses to start (a bad provider table must not read as `no servers`).
   The fallback carries HTTP headers and bearer-token env names for import;
   Codex's env_http_headers and http_headers_helper remain unsupported."
  []
  (let [{:keys [exit out]} (codex-sh "codex" "mcp" "list" "--json")]
    (if (zero? exit)
      (into {} (map (juxt :name identity)) (json/parse-string out true))
      (into {} (map (fn [[nm t]]
                      [nm {:name nm
                           :transport {:command (get t "command")
                                       :args (get t "args")
                                       :url (get t "url")
                                       :http_headers (get t "http_headers")
                                       :bearer_token_env_var (get t "bearer_token_env_var")}
                           :enabled (get t "enabled" true)}]))
            (get (toml/read-toml config-file) "mcp_servers")))))

;; ---------------------------------------------------------------- settings

(defn- keymap-ops [keymap file]
  ;; Keep native context/action spelling. Codex validates which actions and
  ;; key names its installed version supports; only validate the value shape.
  (when-not (and (map? keymap)
                 (every? (fn [[context actions]]
                           (and (or (keyword? context) (string? context))
                                (map? actions)
                                (every? (fn [[action binding]]
                                          (and (or (keyword? action) (string? action))
                                               (or (string? binding)
                                                   (and (sequential? binding)
                                                        (every? string? binding)))))
                                        actions)))
                         keymap))
    (throw (ex-info "codex :keymap must map contexts to actions with string or string-vector bindings"
                    {:path [:executors :codex :keymap]})))
  (keep (fn [[context actions]]
          (plan/toml-set-op {:tool tool :kind :settings
                             :id (keyword "keymap" (u/kw->str context))
                             :file file :table ["tui" "keymap" (u/kw->str context)]
                             :kvs actions
                             :field-prefix (str "keymap." (u/kw->str context))
                             :summary (str "codex keymap " (u/kw->str context))}))
        keymap))

(defn- by-name
  "The value a declared map holds under the native name `k`: its keys may be
   keywords, strings or numbers, the table path is always strings."
  [m k]
  (when (map? m) (some (fn [[dk v]] (when (= k (u/key-str dk)) v)) m)))

(defn owned-keys
  "Native keys the declared `settings` still hold in the config.toml table at
   `path` (strings). Ownership prunes a key agentctl once wrote there only
   when it is missing from this set."
  [settings path]
  (let [path (vec path)
        names (fn [m] (if (map? m) (set (map u/key-str (keys m))) #{}))]
    (cond
      (empty? path) (set (keep setting-keys (keys settings)))
      (= ["tui"] path) (if (schema/status-line? (:status-line settings)) #{"status_line"} #{})
      (and (<= 2 (count path)) (= "permissions" (first path)))
      (names (reduce by-name (by-name (:permission-profiles settings) (second path)) (drop 2 path)))
      (and (= 3 (count path)) (= ["tui" "keymap"] (subvec path 0 2)))
      (names (by-name (:keymap settings) (nth path 2)))
      (and (= 3 (count path)) (= ["tui" "presets"] (subvec path 0 2)))
      (names (some (fn [[k p]] (when (= (nth path 2) (schema/preset-slot k)) p)) (:presets settings)))
      :else #{})))

(defn- native
  "A declared value as config.toml holds it: keywords become strings and a
   map is keyed by full string names."
  [v]
  (cond
    (keyword? v) (u/key-str v)
    (map? v) (into {} (map (fn [[k x]] [(u/key-str k) (native x)])) v)
    (sequential? v) (mapv native v)
    :else v))

(defn whole-table?
  "Is the config.toml table at `path` owned whole rather than key by key? A
   preset slot is: Codex saves its own presets into the same slots, and a
   slot left with a `label` and no `input` is a load error (`missing field
   input`). Declaring slot N replaces whatever was saved there; dropping it
   removes the slot."
  [path]
  (let [path (vec path)]
    (and (= 3 (count path)) (= ["tui" "presets"] (subvec path 0 2)))))

(defn- preset-ops
  "One `[tui.presets.<n>]` table per declared slot, made to hold exactly the
   declared fields (`whole-table?`). Codex writes its own state into `[tui]`
   (`model_availability_nux`), so nothing above the slot is replaced."
  [presets file]
  (let [current (toml/read-toml file)]
    (for [[k p] (sort-by (comp str key) presets)
          :let [n (schema/preset-slot k)
                table ["tui" "presets" n]
                want (native p)
                have (get-in current table)
                diffs (plan/field-diffs (or have {}) want)]
          :when (seq diffs)]
      (plan/op {:action (if have :update :create) :tool tool :kind :settings
                :id (keyword (str "preset-" n)) :target file :path table
                :summary (str "codex preset " n) :diffs diffs :risk :low
                :field-prefix (str "presets." n)
                :exec! (fn []
                         (u/backup! file)
                         (toml/update-file! file #(toml/set-table % table want)))}))))

(defn- status-line-op
  "The native footer: `[tui] status_line`, an item list. One key of `[tui]`,
   set by surgery — the table also holds Codex's own state."
  [items file]
  (plan/toml-set-op {:tool tool :kind :settings :id :status-line
                     :file file :table ["tui"]
                     :kvs {"status_line" (mapv u/key-str items)}
                     :summary "codex footer items"}))

(defn- op-id
  "An id that reads back from state.edn: a table path may hold `/` and spaces."
  [prefix parts]
  (keyword (str prefix (str/join "." (map #(str/replace % #"[^A-Za-z0-9_.-]+" "_") parts)))))

(defn permission-profile-ops
  "`[permissions.<name>]` tables for the declared profiles, written into
   `file` key by key (`plan/toml-tree-ops`): a profile is owned per key, so a
   rule added by hand to a declared profile stays. A profile is plain data in
   Codex's own spelling: `{:extends \":workspace\" :filesystem {path mode}
   :network {:enabled true :domains {host \"allow\"}}}`."
  [profiles file]
  (mapcat (fn [[nm p]]
            (let [nm (u/key-str nm)]
              (plan/toml-tree-ops {:tool tool :kind :settings :file file
                                   :table ["permissions" nm] :value (native p)
                                   :summary (str "codex permission profile " nm)
                                   :field-prefix (str "permission-profiles." nm)
                                   :id-for #(op-id "profile-" (cons nm %))})))
          (sort-by (comp str key) profiles)))

(defn- owned-paths
  "`[table… key]` for every key agentctl recorded writing in config.toml."
  [st file]
  (for [[_ w] (:writes st)
        :when (and (= tool (:tool w)) (= file (:target w)))
        d (:diffs w)]
    (conj (mapv u/kw->str (:path w)) (u/kw->str (:key d)))))

(defn- leaves [m prefix]
  (if (and (map? m) (seq m))
    (mapcat (fn [[k v]] (leaves v (conj prefix k))) m)
    [prefix]))

(defn permissions-after
  "The default profile and the profiles config.toml holds once this apply is
   done: what is declared, plus what is on disk and will not be pruned —
   anything agentctl did not write, and under the legacy `:executors` form,
   which never prunes, everything."
  [settings st entity-dsl? file]
  (let [current (toml/read-toml file)
        owned (if entity-dsl? (vec (owned-paths st file)) [])
        owned? (fn [leaf] (some #(and (<= (count %) (count leaf)) (= % (subvec leaf 0 (count %)))) owned))
        declared (set (map u/key-str (keys (:permission-profiles settings))))
        kept (set (for [[p m] (get current "permissions")
                        :when (and (not (declared p)) (not-every? owned? (leaves m ["permissions" p])))]
                    p))]
    {:default (cond (contains? settings :sandbox) (some-> (:sandbox settings) u/key-str)
                    (owned? ["default_permissions"]) nil
                    :else (get current "default_permissions"))
     :declared declared
     :kept kept
     :profiles (into declared kept)
     :involved? (boolean (or (contains? settings :sandbox) (seq declared)
                             (some #(#{"permissions" "default_permissions"} (first %)) owned)))}))

(defn permission-findings
  "[level where message] for what Codex 0.169.0 would make of config.toml's
   permissions after this apply: it refuses to start on profiles with no
   default, on a default naming no profile the file holds, and on an
   unknown built-in. Checked only when agentctl touches permissions, so a
   file broken by hand does not block an unrelated setting. `file` defaults to
   this machine's config.toml; a host's is read into a local copy."
  ([settings st entity-dsl?] (permission-findings settings st entity-dsl? config-file))
  ([settings st entity-dsl? file]
  (let [{:keys [default declared kept profiles involved?]} (permissions-after settings st entity-dsl? file)
        names (str "declared profiles: " (if (seq declared) (str/join ", " (sort declared)) "none")
                   (when (seq kept) (str "; left in config.toml: " (str/join ", " (sort kept))))
                   "; built-in: " (str/join ", " (sort schema/default-builtin-profiles)))
        error (fn [msg] [[:error [:executors :codex :sandbox] (str "codex would refuse config.toml: " msg " (" names ")")]])]
    (when involved?
      (cond
        (and (seq profiles) (nil? default))
        (error "it would hold [permissions] profiles and no default_permissions — declare :sandbox naming one")
        (nil? default) nil
        (str/starts-with? default ":")
        (when-not (schema/default-builtin-profiles default)
          (error (str "default_permissions " (pr-str default) " is no built-in profile")))
        (not (profiles default))
        (error (str "default_permissions " (pr-str default) " names no profile it would hold")))))))

(def nested-settings
  "Declared settings with an op of their own rather than a root key."
  [:keymap :presets :status-line :permission-profiles :hooks])

(defn settings-ops
  "`file` is the config.toml the ops edit: this machine's, or the local copy
   of a host's (`agentctl.hosts`)."
  ([cfg] (settings-ops cfg nil))
  ([cfg st] (settings-ops cfg st config-file))
  ([cfg st file]
  (let [settings (common/settings-for cfg tool)
        _ (when-let [e (schema/errors settings [:executors :codex])]
            (throw (ex-info (str "codex settings: " e) {:path [:executors :codex]})))
        ;; a run that writes codex settings is refused on these; any other
        ;; run only warns (`core/gate-codex-permissions`)
        gate (when-let [es (seq (filter #(= :error (first %)) (permission-findings settings st (:entity-dsl? cfg) file)))]
               (plan/op {:action :noop :warn true :tool tool :kind :settings :id :permissions
                         :refuse-if-settings-change true
                         :summary (str/join "; " (map last es))}))
        ;; Claude's status line is a command object; in a unified settings
        ;; row it reaches codex too, and stays an unsupported setting here
        footer? (schema/status-line? (:status-line settings))
        [kvs unsupported] (common/map-settings (apply dissoc settings (cond->> nested-settings
                                                                        (not footer?) (remove #{:status-line})))
                                               setting-keys)
        bindings (when (contains? settings :keymap) (keymap-ops (:keymap settings) file))]
    (concat
     (keep identity
           [(plan/toml-set-op {:tool tool :kind :settings :id :global
                               :file file :table [] :kvs kvs
                               :summary "global codex settings"})])
     bindings
     (preset-ops (:presets settings) file)
     (when footer? (keep identity [(status-line-op (:status-line settings) file)]))
     (permission-profile-ops (:permission-profiles settings) file)
     (when gate [gate])
     (for [k unsupported]
       (plan/op {:action :noop :tool tool :kind :settings :id k
                 :summary (str "unsupported setting " k " for codex — ignored")
                 :warn true}))))))

;; ---------------------------------------------------------------- hooks

(defn hook-command
  "One `hooks[event][].hooks[]` entry: a command handler, the only kind the
   DSL spells for codex."
  [decl]
  (u/prune-nils
   (merge {:type "command"}
          (into {} (keep (fn [[k v]] (when-let [nk (get schema/hook-entry-keys k)] [nk v]))) decl))))

(defn hook-group
  "One element of `hooks[event]`: an optional matcher and the one command."
  [decl]
  (u/prune-nils {:matcher (:matcher decl) :hooks [(hook-command decl)]}))

(defn hook-path [decl] [:hooks (keyword (u/kw->str (:event decl)))])

(defn hook-ops
  "User-wide hooks declared under `:executors :codex :hooks`, in
   `~/.codex/hooks.json`. Each id owns one `hooks[event]` element, found by
   value (`json-array-merge-op`), so a vendor's hooks in the same array are
   neither touched nor reported.

   Codex keys hook trust by position (`[hooks.state.\"<file>:<event>:<group>:<idx>\"]`
   in config.toml, never written here). A changed hook is replaced at its
   own index so only it needs review again; a new one is appended after
   everything already trusted. Removing one shifts the groups after it in
   that event, and Codex asks for their review again."
  [cfg st]
  (let [declared (:hooks (common/settings-for cfg tool))
        managed (into #{} (map keyword) (state/managed-ids st tool :hooks))]
    (concat
     (mapcat (fn [[id decl]]
               (let [path (hook-path decl)
                     entry (state/entry st tool :hooks id)]
                 (plan/json-array-place-ops
                  {:tool tool :kind :hooks :id id
                   :file hooks-file :path path :old (:value entry) :old-path (:path entry)
                   :value (hook-group decl)
                   :report-converged? true :in-place? true
                   :note "codex asks to review a new or changed hook before it runs"
                   :summary (str "hooks.json hooks." (name (last path))
                                 (when-let [m (:matcher decl)] (str " [" m "]")))})))
             declared)
     (for [id managed
           :when (not (contains? declared id))
           :let [entry (state/entry st tool :hooks id)
                 op (when (and entry (:path entry))
                      (plan/json-array-unset-op
                       {:tool tool :kind :hooks :id id
                        :file hooks-file :path (:path entry) :old (:value entry)
                        :summary "removed from agents.edn"}))]
           :when op]
       op))))

;; ---------------------------------------------------------------- mcps

(defn- mcp-desired-shape [m headers]
  (u/prune-nils
   {:command (:command m)
    :args (vec (:args m))
    :url (:url m)
    :bearer-token-env (:bearer-token-env m)
    :headers (not-empty headers)}))

(defn- mcp-current-shape [entry]
  (let [t (:transport entry)]
    (u/prune-nils
     {:command (:command t)
      :args (vec (:args t))
      :url (:url t)
      :bearer-token-env (:bearer_token_env_var t)
      :headers (not-empty (:http_headers t))})))

(defn mcp-ops [cfg state]
  (let [desired (common/for-tool-resources (common/global-mcps (:mcps cfg) tool) tool)
        current (mcp-list)
        managed (into #{} (map keyword) (state/managed-ids state tool :mcps))]
    (concat
     (for [[id m] desired
           :let [name* (u/kw->str id)
                 cur (get current name*)
                 env (common/resolve-env (:env m))
                 headers (common/resolve-env (:headers m))
                 issues (concat (common/issues-in "env" env) (common/issues-in "headers" headers))
                 want (mcp-desired-shape m (:values headers))
                 have (some-> cur mcp-current-shape)]
           ;; an unresolved env or header ref holds the server back: written
           ;; without it, it would run and authenticate nothing
           :when (or (seq issues) (not= (u/norm want) (u/norm have)))]
       (if (seq issues)
         (common/unresolved-op {:tool tool :kind :mcps :id id :target config-file} issues)
         (plan/op {:action (if cur :update :create)
                 :tool tool :kind :mcps :id id
                 :target config-file
                 :summary (common/mcp-summary m)
                 :note (when (common/holds-secret? (merge (:env m) (:headers m)))
                         "⚠ writes resolved secret to config.toml")
                 :diffs (plan/field-diffs (or have {}) want)
                 :risk (if (common/holds-secret? (merge (:env m) (:headers m))) :secret :low)
                 :exec! (fn []
                          (u/backup! config-file)
                          (when cur (ok! (codex-sh "codex" "mcp" "remove" name*) "codex mcp remove"))
                          (let [env-args (mapcat (fn [[k v]] ["--env" (str k "=" v)]) (:values env))]
                            (ok! (if (= :http (:transport m))
                                   (codex-sh "codex" "mcp" "add" name* "--url" (:url m)
                                             (when (:bearer-token-env m)
                                               ["--bearer-token-env-var" (:bearer-token-env m)]))
                                   (codex-sh (concat ["codex" "mcp" "add" name*] env-args
                                                     ["--"] [(:command m)] (:args m))))
                                 (str "codex mcp add " name*)))
                          (when (seq (:values headers))
                            (toml/update-file! config-file
                                               #(toml/set-keys % ["mcp_servers" name* "http_headers"]
                                                               (:values headers))))
                          (when-not (:enabled m)
                            (toml/update-file! config-file
                                               #(toml/set-key % ["mcp_servers" name*] :enabled false))))})))
     ;; prune servers we own that left the DSL
     (for [id managed
           :when (and (nil? (namespace id))
                      (not (contains? desired id))
                      (get current (u/kw->str id)))]
       (plan/op {:action :delete :tool tool :kind :mcps :id id
                 :target config-file
                 :summary "removed from agents.edn"
                 :risk :medium
                 :exec! (fn [] (u/backup! config-file)
                          (ok! (codex-sh "codex" "mcp" "remove" (u/kw->str id)) "codex mcp remove"))})))))

;; ---------------------------------------------------------------- project mcps

(defn project-config-file
  "Codex layers `<project>/.codex/config.toml` over the user config, for a
   trusted project only."
  [proj] (str (:path proj) "/.codex/config.toml"))

(defn- project-mcp-table
  "The `[mcp_servers.<id>]` table as codex itself writes it."
  [m env headers]
  (u/prune-nils
   {"command" (:command m)
    "args" (when (:command m) (vec (:args m)))
    "url" (:url m)
    "bearer_token_env_var" (:bearer-token-env m)
    "env" (not-empty env)
    "http_headers" (not-empty headers)
    "enabled" (when-not (:enabled m) false)}))

(defn- trusted?
  "Codex ignores an untrusted project's config without a word. Trust is per
   exact project root, never inherited from a parent directory. A declared
   trust is the one `project-ops` writes."
  [proj]
  (or (true? (:trusted proj))
      (= "trusted" (get-in (toml/read-toml config-file)
                           ["projects" (:path proj) "trust_level"]))))

(defn- project-mcp-ops [cfg pid proj]
  (let [file (project-config-file proj)
        servers (get (toml/read-toml file) "mcp_servers")
        ms (common/project-mcps cfg proj tool)]
    (concat
     (when (and (seq ms) (not (trusted? proj)))
       [(plan/op {:action :noop :warn true :tool tool :kind :mcps :project pid
                  :id (keyword (str (name pid) "/trust"))
                  :summary (str (u/tilde (:path proj)) " is not trusted — codex ignores its "
                                ".codex/config.toml; add a :trust row for :codex")})])
     (for [[mid m] ms
           :let [m (common/for-this-tool m tool)
                 name* (u/kw->str mid)
                 id (keyword (name pid) name*)
                 env (common/resolve-env (:env m))
                 headers (common/resolve-env (:headers m))
                 issues (concat (common/issues-in "env" env) (common/issues-in "headers" headers))
                 have (get servers name*)
                 want (project-mcp-table m (:values env) (:values headers))
                 secret? (common/holds-secret? (merge (:env m) (:headers m)))]
           :when (or (seq issues) (not= (u/norm want) (u/norm have)))]
       (if (seq issues)
         (common/unresolved-op {:tool tool :kind :mcps :project pid :id id :target file} issues)
         (plan/op {:action (if have :update :create)
                   :tool tool :kind :mcps :project pid :id id
                   :target file
                   :path ["mcp_servers" name*]
                   :summary (common/mcp-summary m)
                   :note (when secret? "⚠ secret value written into the project — .codex/ may be tracked")
                   :diffs (plan/field-diffs (or have {}) want)
                   :risk (if secret? :secret :low)
                   ;; the whole table is rewritten: a key left from an
                   ;; earlier shape (stdio → http) would change what codex runs
                   :exec! (fn []
                            (u/backup! file)
                            (toml/update-file! file #(-> (toml/remove-table % ["mcp_servers" name*])
                                                         (toml/set-keys ["mcp_servers" name*] want))))}))))))

(defn- project-mcp-prune-ops
  "A project's server dropped from agents.edn. The path comes from the state
   when the project itself is gone from the config."
  [cfg st]
  (let [desired (into #{} (for [[pid proj] (:projects cfg)
                                :when (contains? (:for-tools proj) tool)
                                [mid _] (common/project-mcps cfg proj tool)]
                            (keyword (name pid) (u/kw->str mid))))]
    (for [id (map keyword (state/managed-ids st tool :mcps))
          :when (and (namespace id) (not (contains? desired id)))
          :let [path (or (get-in cfg [:projects (keyword (namespace id)) :path])
                         (:project-path (state/entry st tool :mcps id)))]
          :when path
          :let [op (plan/toml-remove-table-op {:tool tool :kind :mcps :id id
                                               :project (keyword (namespace id))
                                               :file (project-config-file {:path path})
                                               :table ["mcp_servers" (name id)]
                                               :summary "removed from agents.edn"})]
          :when op]
      op)))

;; ---------------------------------------------------------------- providers

(defn provider-ops [cfg]
  (let [desired (common/for-tool-resources (:providers cfg) tool)]
    (for [[id p] desired
          :let [key-ref (:key p)
                env-var (when (= :env (:ref/kind key-ref)) (:ref/var key-ref))
                current-name (get-in (toml/read-toml config-file)
                                     ["model_providers" (u/kw->str id) "name"])
                kvs (u/prune-nils
                     {"base_url" (:url p)
                      ;; codex owns the display name once it exists
                      "name" (or current-name (str/capitalize (name id)))
                      ;; codex dropped `wire_api = "chat"`; responses is the only accepted value
                      "wire_api" "responses"
                      "env_key" env-var
                      "requires_openai_auth" false})
                op (plan/toml-set-op {:tool tool :kind :providers :id id
                                      :file config-file
                                      :table ["model_providers" (u/kw->str id)]
                                      :kvs kvs
                                      :summary (str "provider " (:url p))
                                      :note (when-not env-var
                                              "⚠ codex only reads keys from env — declare :key $VAR")})]
          :when op]
      op)))

;; ---------------------------------------------------------------- projects

(defn project-skills-dir
  "Codex scans `.agents/skills` from the working directory up to the repo root."
  [proj] (str (:path proj) "/.agents/skills"))

(defn project-ops [cfg st]
  (concat
   (for [[id proj] (:projects cfg)
         :when (and (some? (:trusted proj))
                    (contains? (:for-tools proj) tool))
         :let [op (plan/toml-set-op {:tool tool :kind :projects :project id :id id
                                     :file config-file
                                     :table ["projects" (:path proj)]
                                     :kvs {"trust_level" (if (:trusted proj) "trusted" "untrusted")}
                                     :summary (u/tilde (:path proj))})]
         :when op]
     op)
   (mapcat (fn [[id proj]]
             (when (contains? (:for-tools proj) tool)
               (project-mcp-ops cfg id proj)))
           (:projects cfg))
   (project-mcp-prune-ops cfg st)
   (mapcat (fn [[id proj]]
             (when (contains? (:for-tools proj) tool)
               ;; installs are the `skills` CLI's (`skills-cli`); this drops
               ;; project copies of user-wide skills
               (sources/global-project-unlink-ops cfg tool id (project-skills-dir proj) skills-dir)))
           (:projects cfg))))

;; ---------------------------------------------------------------- skills

(defn skill-ops
  "User-wide skills. A project's skills go to its `.agents/skills`."
  [cfg state]
  (let [desired (sources/for-tool cfg
                                  (into {} (filter (fn [[_ s]] (= :global (:scope s))))
                                        (sources/all-skills cfg))
                                  tool)
        managed (into #{} (map keyword) (state/managed-ids state tool :skills))]
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

;; ---------------------------------------------------------------- entry

(defn plan [cfg state]
  (concat (settings-ops cfg state)
          (hook-ops cfg state)
          (mcp-ops cfg state)
          (provider-ops cfg)
          ;; the user-wide links first: a project copy's removal checks for them
          (skill-ops cfg state)
          (project-ops cfg state)
          (memory-ops cfg)))

(defn present? [] (some? (u/which "codex")))
