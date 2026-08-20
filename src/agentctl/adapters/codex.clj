(ns agentctl.adapters.codex
  "Codex CLI adapter.

   MCP servers go through `codex mcp add|remove` (verified to rewrite only the
   relevant table). Everything else is TOML key surgery so codex-owned state
   ([hooks.state], [plugins.*], trust dialogs) is preserved."
  (:require [agentctl.adapters.common :as common]
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

(defn codex-sh
  "Run the codex CLI against the home directory agentctl is managing."
  [& args]
  (u/sh-env {:CODEX_HOME home} args))

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
   CLI refuses to start (a bad provider table must not read as `no servers`)."
  []
  (let [{:keys [exit out]} (codex-sh "codex" "mcp" "list" "--json")]
    (if (zero? exit)
      (into {} (map (juxt :name identity)) (json/parse-string out true))
      (into {} (map (fn [[nm t]]
                      [nm {:name nm
                           :transport {:command (get t "command")
                                       :args (get t "args")
                                       :url (get t "url")}
                           :enabled (get t "enabled" true)}]))
            (get (try (toml/read-toml config-file) (catch Exception _ nil)) "mcp_servers")))))

;; ---------------------------------------------------------------- settings

(defn settings-ops [cfg]
  (let [settings (common/settings-for cfg tool)
        [kvs unsupported] (common/map-settings settings setting-keys)]
    (concat
     (keep identity
           [(plan/toml-set-op {:tool tool :kind :settings :id :global
                               :file config-file :table [] :kvs kvs
                               :summary "global codex settings"})])
     (for [k unsupported]
       (plan/op {:action :noop :tool tool :kind :settings :id k
                 :summary (str "unsupported setting " k " for codex — ignored")
                 :warn true})))))

;; ---------------------------------------------------------------- mcps

(defn- mcp-desired-shape [m]
  (u/prune-nils
   {:command (:command m)
    :args (vec (:args m))
    :url (:url m)}))

(defn- mcp-current-shape [entry]
  (let [t (:transport entry)]
    (u/prune-nils
     {:command (:command t)
      :args (vec (:args t))
      :url (:url t)})))

(defn mcp-ops [cfg state]
  (let [desired (common/for-tool-resources (common/global-mcps (:mcps cfg) tool) tool)
        current (mcp-list)
        managed (into #{} (map keyword) (state/managed-ids state tool :mcps))]
    (concat
     (for [[id m] desired
           :let [name* (u/kw->str id)
                 cur (get current name*)
                 want (mcp-desired-shape m)
                 have (some-> cur mcp-current-shape)
                 env (common/resolve-env (:env m))]
           :when (not= (u/norm want) (u/norm have))]
       (plan/op {:action (if cur :update :create)
                 :tool tool :kind :mcps :id id
                 :target config-file
                 :summary (common/mcp-summary m)
                 :note (when (common/holds-secret? (:env m)) "⚠ writes resolved secret to config.toml")
                 :diffs (plan/field-diffs (or have {}) want)
                 :risk (if (common/holds-secret? (:env m)) :secret :low)
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
                          (when-not (:enabled m)
                            (toml/update-file! config-file
                                               #(toml/set-key % ["mcp_servers" name*] :enabled false))))}))
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

;; ---------------------------------------------------------------- providers

(defn provider-ops [cfg]
  (let [desired (common/for-tool-resources (:providers cfg) tool)]
    (for [[id p] desired
          :let [key-ref (:key p)
                env-var (when (= :env (:ref/kind key-ref)) (:ref/var key-ref))
                current-name (get-in (try (toml/read-toml config-file) (catch Exception _ nil))
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

(defn project-ops [cfg]
  (for [[id proj] (:projects cfg)
        :when (and (some? (:trusted proj))
                   (contains? (:for-tools proj) tool))
        :let [op (plan/toml-set-op {:tool tool :kind :projects :project id :id id
                                    :file config-file
                                    :table ["projects" (:path proj)]
                                    :kvs {"trust_level" (if (:trusted proj) "trusted" "untrusted")}
                                    :summary (u/tilde (:path proj))})]
        :when op]
    op))

;; ---------------------------------------------------------------- skills

(defn skill-ops [cfg state]
  (let [desired (sources/for-tool cfg (sources/all-skills cfg) tool)
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
     (for [id managed
           :when (not (contains? desired id))
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
  (concat (settings-ops cfg)
          (mcp-ops cfg state)
          (common/project-scope-skip-ops cfg tool)
          (provider-ops cfg)
          (project-ops cfg)
          (skill-ops cfg state)
          (memory-ops cfg)))

(defn present? [] (some? (u/which "codex")))
