(ns agentctl.adapters.pi
  "pi adapter — plain JSON config files under ~/.pi/agent."
  (:require [agentctl.adapters.common :as common]
            [agentctl.plan :as plan]
            [agentctl.refs :as refs]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.util :as u]))

(def tool :pi)
(def home (str u/home "/.pi/agent"))
(def settings-file (str home "/settings.json"))
(def mcp-file (str home "/mcp.json"))
(def models-file (str home "/models.json"))
(def trust-file (str home "/trust.json"))
(def skills-dir (str home "/skills"))
(def memory-file (str home "/AGENTS.md"))

(def setting-keys
  {:model :defaultModel
   :provider :defaultProvider
   :thinking :defaultThinkingLevel
   :theme :theme})

(defn settings-ops [cfg]
  (let [settings (common/settings-for cfg tool)
        [kvs unsupported] (common/map-settings settings setting-keys)]
    (concat
     (keep (fn [[k v]]
             (plan/json-set-op {:tool tool :kind :settings :id k
                                :file settings-file :path [k] :value v
                                :summary (str "settings.json " (name k))}))
           kvs)
     (for [k unsupported]
       (plan/op {:action :noop :tool tool :kind :settings :id k
                 :summary (str "unsupported setting " k " for pi — ignored") :warn true})))))

(defn- mcp-entry
  "Managed fields merged onto whatever pi already stores, so tool-native keys
   the DSL does not model (lifecycle, idleTimeout, …) survive."
  [m cur]
  (let [env (common/resolve-env (:env m))]
    (merge cur
           (:extra m)
           (u/prune-nils
            {:command (:command m)
             :args (not-empty (vec (:args m)))
             :url (:url m)
             :env (not-empty (:values env))
             :enabled (when (false? (:enabled m)) false)}))))

(defn mcp-ops [cfg st]
  (let [desired (common/for-tool-resources (common/global-mcps (:mcps cfg) tool) tool)
        current (or (:mcpServers (u/read-json mcp-file)) {})
        managed (into #{} (map keyword) (state/managed-ids st tool :mcps))]
    (concat
     (keep (fn [[id m]]
             (plan/json-set-op {:tool tool :kind :mcps :id id
                                :file mcp-file :path [:mcpServers (keyword (u/kw->str id))]
                                :value (mcp-entry m (get current (keyword (u/kw->str id))))
                                :risk (if (common/holds-secret? (:env m)) :secret :low)
                                :summary (common/mcp-summary m)}))
           desired)
     (keep (fn [id]
             (when (and (nil? (namespace id))
                        (not (contains? desired id))
                        (get current (keyword (u/kw->str id))))
               (plan/json-remove-op {:tool tool :kind :mcps :id id
                                     :file mcp-file
                                     :path [:mcpServers (keyword (u/kw->str id))]})))
           managed))))

(defn- provider-entry [p cur]
  (let [{:keys [status value]} (refs/resolve-ref (:key p))]
    (u/prune-nils
     {:baseUrl (:url p)
      :api (:api p)
      ;; an unresolvable ref (locked vault, unset env) must never wipe a working key
      :apiKey (if (= :ok status) value (:apiKey cur))
      :models (when (vector? (:models p))
                (mapv (fn [id] {:id id :name id}) (:models p)))})))

(defn provider-ops [cfg]
  (mapcat
   (fn [[id p]]
     (let [cur (get-in (u/read-json models-file) [:providers (keyword (u/kw->str id))])
           entry (provider-entry p cur)
           value (merge (or cur {}) entry)
           {:keys [status message]} (refs/resolve-ref (:key p))]
       (remove nil?
               [(when (and (:key p) (not= :ok status))
                  (plan/op {:action :noop :tool tool :kind :providers :id id :warn true
                            :summary (str "key " (refs/describe (:key p)) " unresolved (" (or message (name status))
                                          ") — existing credential left untouched")}))
                (plan/json-set-op {:tool tool :kind :providers :id id
                                   :file models-file
                                   :path [:providers (keyword (u/kw->str id))]
                                   :value value
                                   :risk (if (refs/secret-ref? (:key p)) :secret :low)
                                   :summary (str "models.json providers." (u/kw->str id))})])))
   (common/for-tool-resources (:providers cfg) tool)))

(defn project-ops [cfg]
  (keep (fn [[id proj]]
          (when (and (some? (:trusted proj))
                     (contains? (:for-tools proj) tool))
            (plan/json-set-op {:tool tool :kind :projects :project id :id id
                               :file trust-file :path [(keyword (:path proj))]
                               :value (boolean (:trusted proj))
                               :summary (str "trust " (u/tilde (:path proj)))})))
        (:projects cfg)))

(defn skill-ops [cfg st]
  (let [desired (sources/for-tool cfg (sources/all-skills cfg) tool)
        managed (into #{} (map keyword) (state/managed-ids st tool :skills))]
    (concat
     (keep (fn [[id s]]
             (when (:source s)
               (plan/link-op {:tool tool :kind :skills :id id :src (:source s)
                              :dest (str skills-dir "/" (name id)) :mode (:mode s)})))
           desired)
     (keep (fn [id]
             (when-not (contains? desired id)
               (plan/unlink-op {:tool tool :kind :skills :id id
                                :dest (str skills-dir "/" (name id))})))
           managed))))

(defn memory-ops [cfg]
  (keep (fn [[id m]]
          (when (= :global (:scope m))
            (plan/link-op {:tool tool :kind :memory :id id :src (:from m)
                           :dest memory-file :mode (:mode m)})))
        (common/for-tool (:memory cfg) tool)))

(defn plan [cfg st]
  (concat (settings-ops cfg) (mcp-ops cfg st)
          (common/project-scope-skip-ops cfg tool) (provider-ops cfg)
          (project-ops cfg) (skill-ops cfg st) (memory-ops cfg)))

(defn present? [] (some? (u/which "pi")))
