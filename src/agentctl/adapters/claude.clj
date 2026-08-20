(ns agentctl.adapters.claude
  "Claude Code adapter.

   settings.json is agentctl-editable (plain config). ~/.claude.json is a large
   runtime state blob owned by the CLI: it is read for current state, and only
   ever mutated through `claude mcp` or narrowly-scoped project keys."
  (:require [agentctl.adapters.common :as common]
            [agentctl.plan :as plan]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.util :as u]
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

(def setting-keys
  {:model :model
   :output-style :outputStyle
   :theme :theme
   :effort :effortLevel
   :thinking :effortLevel
   :auto-compact :autoCompactEnabled
   :ultracode :ultracode
   :skip-auto :skipAutoPermissionPrompt
   :status-line :statusLine
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

(defn settings-ops [cfg]
  (let [settings (common/settings-for cfg tool)
        kvs (declared-settings settings)
        unsupported (remove (set (keys setting-keys)) (keys settings))]
    (concat
     (keep identity
           [(plan/json-set-op {:tool tool :kind :settings :id :$schema
                               :file settings-file :path [:$schema] :value settings-schema-url
                               :summary "settings.json $schema"})])
     ;; keyed by the name agents.edn uses: a reader checking the plan against
     ;; the file they wrote should not have to translate back
     (for [[k [nk v]] kvs
           :let [op (plan/json-set-op {:tool tool :kind :settings :id k
                                       :file settings-file :path [nk] :value v
                                       :report-converged? true
                                       :summary (str "settings.json " (name nk))})]
           :when op]
       op)
     (for [k unsupported]
       (plan/op {:action :noop :tool tool :kind :settings :id k
                 :summary (str "unsupported setting " k " for claude — ignored")
                 :warn true})))))

;; ---------------------------------------------------------------- mcps

(defn current-mcps []
  (or (:mcpServers (u/read-json runtime-file)) {}))

(defn- desired-shape [m]
  (u/prune-nils
   {:type (name (:transport m))
    :command (:command m)
    :args (vec (:args m))
    :url (:url m)}))

(defn- current-shape [entry]
  (u/prune-nils
   {:type (or (:type entry) (if (:url entry) "http" "stdio"))
    :command (:command entry)
    :args (vec (:args entry))
    :url (:url entry)}))

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
                 env (common/resolve-env (:env m))
                 headers (common/resolve-env (:headers m))]
           :when (not= (u/norm want) (u/norm have))]
       (plan/op {:action (if cur :update :create)
                 :tool tool :kind :mcps :id id
                 :target runtime-file
                 :summary (common/mcp-summary m)
                 :note (when (common/holds-secret? (merge (:env m) (:headers m)))
                         "⚠ secret value stored in ~/.claude.json")
                 :diffs (plan/field-diffs (or have {}) want)
                 :risk (if (common/holds-secret? (merge (:env m) (:headers m))) :secret :low)
                 :exec! (fn []
                          (when cur (claude-sh "claude" "mcp" "remove" "--scope" "user" nm))
                          (if (= :http (:transport m))
                            (claude-sh (concat ["claude" "mcp" "add" "--scope" "user"
                                           "--transport" "http" nm (:url m)]
                                          (mapcat (fn [[k v]] ["--header" (str k ": " v)]) (:values headers))))
                            (claude-sh (concat ["claude" "mcp" "add" "--scope" "user" nm]
                                          (mapcat (fn [[k v]] ["-e" (str k "=" v)]) (:values env))
                                          ["--"] [(:command m)] (:args m)))))}))
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
                 :exec! (fn [] (claude-sh "claude" "mcp" "remove" "--scope" "user" (u/kw->str id)))})))))

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

(defn- mcp-json-entry
  "Merged onto whatever .mcp.json already holds: the file is checked into the
   project and may carry servers agentctl knows nothing about."
  [m cur]
  (let [env (common/resolve-env (:env m))
        headers (common/resolve-env (:headers m))]
    (merge cur
           (:extra m)
           (u/prune-nils
            {:type (name (:transport m))
             :command (:command m)
             :args (not-empty (vec (:args m)))
             :url (:url m)
             :env (not-empty (merge (:env cur) (:values env)))
             :headers (not-empty (merge (:headers cur) (:values headers)))}))))

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

(defn- project-mcp-writes [cfg id proj]
  (for [[mid m] (common/project-mcps cfg proj tool)
        :let [{:keys [file path shared?]} (project-mcp-target proj m mid)
              current (get-in (u/read-json file) path)
              secret? (common/holds-secret? (merge (:env m) (:headers m)))
              op (plan/json-set-op
                  {:tool tool :kind :mcps :project id
                   :id (keyword (str (name id) "/" (name mid)))
                   :file file
                   :path path
                   :value (mcp-json-entry m current)
                   :risk (if secret? :secret :low)
                   :summary (common/mcp-summary m)
                   :note (when (and secret? shared?)
                           "⚠ secret value written into the project — .mcp.json is usually tracked")})]
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
          :when (and (namespace id) (not (contains? desired id)))
          :let [proj (get-in cfg [:projects (keyword (namespace id))])]
          ;; a project gone from agents.edn takes its path with it, and the
          ;; manifest never recorded one — nothing left to point a delete at
          :when proj
          [file path] [[runtime-file [:projects (keyword (:path proj))
                                      :mcpServers (keyword (name id))]]
                       [(project-mcp-file proj) [:mcpServers (keyword (name id))]]]
          :let [op (plan/json-unset-op {:tool tool :kind :mcps :id id
                                        :project (keyword (namespace id))
                                        :file file :path path
                                        :summary "removed from agents.edn"})]
          :when op]
      op)))

(defn- project-ops-for [cfg id proj]
  (let [pfile (project-settings-file proj)
        tool-settings (get-in proj [:tools tool])
        [kvs _] (common/map-settings tool-settings setting-keys)]
    (concat
     (keep identity
           [(when (or (seq kvs) (seq (:permissions proj)))
              (plan/json-set-op {:tool tool :kind :projects :project id
                                 :id (keyword (str (name id) "/$schema"))
                                 :file pfile :path [:$schema] :value settings-schema-url
                                 :summary (str (u/tilde pfile) " $schema")}))])
     ;; per-project settings file: model / output style / permissions
     (keep identity
           (concat
            (for [[k v] kvs]
              (plan/json-set-op {:tool tool :kind :projects :project id
                                 :id (keyword (str (name id) "/" (name k)))
                                 :file pfile :path [k] :value v
                                 :summary (str (u/tilde pfile) " " (name k))}))
            ;; one op per declared bucket: writing the whole :permissions map
            ;; would drop defaultMode / additionalDirectories the CLI put there
            (for [[bucket rules] (:permissions proj)]
              (plan/json-set-op {:tool tool :kind :projects :project id
                                 :id (keyword (str (name id) "/permissions." (u/kw->str bucket)))
                                 :file pfile :path [:permissions bucket] :value rules
                                 :compare-as (when (coll? rules) :set)
                                 :summary (str (u/tilde pfile) " permissions." (u/kw->str bucket))}))))
     ;; trust and MCP enablement live in the runtime blob
     (keep identity
           [(when (:trusted proj)
              (plan/json-set-op {:tool tool :kind :projects :project id
                                 :id (keyword (str (name id) "/trust"))
                                 :file runtime-file
                                 :path [:projects (keyword (:path proj)) :hasTrustDialogAccepted]
                                 :value true
                                 :risk :medium
                                 :summary "trust project in ~/.claude.json"}))
            ;; only servers that live in .mcp.json need enabling: a local-scope
            ;; server is already the user's own and this list never names it
            (when-let [shared (not-empty (keys (filter (fn [[_ m]] (= :project (:scope m)))
                                                       (common/project-mcps cfg proj tool))))]
              (plan/json-set-op {:tool tool :kind :projects :project id
                                 :id (keyword (str (name id) "/mcp"))
                                 :file runtime-file
                                 :path [:projects (keyword (:path proj)) :enabledMcpjsonServers]
                                 :value (mapv u/kw->str (sort shared))
                                 :risk :medium
                                 :summary "enable project MCP servers"}))]))))

(defn project-skills-dir [proj] (str (:path proj) "/.claude/skills"))

(defn- project-skill-ops
  "Skills a project named, linked into the project's own `.claude/skills`.

   A link that already exists but points somewhere else — a hand-made link into
   `$TOOLS`, or a dangling relative one — is a change, not a conflict: the pack
   cache is the single copy agentctl keeps current, and the project should read
   from it."
  [cfg id proj st]
  (let [{:keys [skills pending unknown]} (sources/project-skills cfg proj)
        dir (project-skills-dir proj)
        managed (into #{} (map keyword) (state/managed-ids st tool :skills))]
    (concat
     (for [[sid s] skills
           :when (:source s)
           :let [op (plan/link-op {:tool tool :kind :skills :project id
                                   :id (keyword (name id) (name sid))
                                   :src (:source s)
                                   :dest (str dir "/" (name sid))
                                   :mode (or (:mode s) :symlink)})]
           :when op]
       op)
     (for [pid pending]
       (plan/op {:action :noop :warn true :tool tool :kind :skills :project id
                 :id (keyword (name id) (name pid))
                 :summary (str "pack not fetched yet — its skills are linked once "
                               (name pid) " is cloned")}))
     (for [uid unknown]
       (plan/op {:action :noop :warn true :tool tool :kind :skills :project id
                 :id (keyword (name id) (name uid))
                 :summary (str "no skill or skill-pack named " uid " — nothing to link")}))
     ;; ours to remove: this project's own skills, dropped from agents.edn
     (for [mid managed
           :when (and (= (name id) (namespace mid))
                      (not (contains? skills (keyword (name mid)))))
           :let [op (plan/unlink-op {:tool tool :kind :skills :project id :id mid
                                     :dest (str dir "/" (name mid))})]
           :when op]
       op))))

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
          (mcp-ops cfg st)
          (skill-ops cfg st)
          (memory-ops cfg)
          (project-ops cfg st)))

(defn present? [] (some? (u/which "claude")))
