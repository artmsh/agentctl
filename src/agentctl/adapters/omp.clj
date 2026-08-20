(ns agentctl.adapters.omp
  "omp (oh-my-pi) adapter — YAML config + JSON mcp registry under ~/.omp/agent."
  (:require [agentctl.adapters.common :as common]
            [agentctl.plan :as plan]
            [agentctl.refs :as refs]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.util :as u]))

(def tool :omp)
(def home (str u/home "/.omp/agent"))
(def config-file (str home "/config.yml"))
(def models-file (str home "/models.yml"))
(def mcp-file (str home "/mcp.json"))
(def skills-dir (str home "/skills"))
(def memory-file (str home "/AGENTS.md"))

(def setting-paths
  {:model [:modelRoles :default]
   :personality [:personality]
   :thinking [:defaultThinkingLevel]
   :theme [:theme :dark]
   :edit-mode [:edit :mode]})

(defn settings-ops [cfg]
  (let [settings (common/settings-for cfg tool)
        roles (:model-roles settings)]
    (concat
     (keep (fn [[k v]]
             (when-let [path (get setting-paths k)]
               (plan/yaml-set-op {:tool tool :kind :settings :id k
                                  :file config-file :path path :value v
                                  :summary (str "config.yml " (clojure.string/join "." (map name path)))})))
           (dissoc settings :model-roles))
     (keep (fn [[role v]]
             (plan/yaml-set-op {:tool tool :kind :settings :id (keyword (str "role/" (name role)))
                                :file config-file :path [:modelRoles role] :value v
                                :summary (str "config.yml modelRoles." (name role))}))
           roles)
     (for [k (remove (set (conj (keys setting-paths) :model-roles)) (keys settings))]
       (plan/op {:action :noop :tool tool :kind :settings :id k
                 :summary (str "unsupported setting " k " for omp — ignored") :warn true})))))

(defn- mcp-entry
  "Managed fields merged onto omp's existing entry — unmodelled keys survive."
  [m cur]
  (let [env (common/resolve-env (:env m))]
    (merge cur
           (:extra m)
           (u/prune-nils
            {:type (name (:transport m))
             :command (:command m)
             :args (not-empty (vec (:args m)))
             :url (:url m)
             :env (not-empty (:values env))
             :enabled (:enabled m)}))))

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
               (plan/json-remove-op {:tool tool :kind :mcps :id id :file mcp-file
                                     :path [:mcpServers (keyword (u/kw->str id))]})))
           managed))))

(defn- provider-entry [p cur]
  (let [{:keys [status value]} (refs/resolve-ref (:key p))]
    (merge (or cur {})
           (u/prune-nils
            {:baseUrl (:url p)
             :api (:api p)
             :auth (if (= :ok status) "apiKey" (:auth cur))
             ;; an unresolvable ref (locked vault, unset env) must never wipe a working key
             :apiKey (if (= :ok status) value (:apiKey cur))
             :modelOverrides (:overrides p)
             :models (when (vector? (:models p))
                       (mapv (fn [id] {:id id :name id}) (:models p)))}))))

(defn provider-ops [cfg]
  (mapcat
   (fn [[id p]]
     (let [cur (get-in (u/read-yaml models-file) [:providers (keyword (u/kw->str id))])
           {:keys [status message]} (refs/resolve-ref (:key p))]
       (remove nil?
               [(when (and (:key p) (not= :ok status))
                  (plan/op {:action :noop :tool tool :kind :providers :id id :warn true
                            :summary (str "key " (refs/describe (:key p)) " unresolved (" (or message (name status))
                                          ") — existing credential left untouched")}))
                (plan/yaml-set-op {:tool tool :kind :providers :id id
                                   :file models-file
                                   :path [:providers (keyword (u/kw->str id))]
                                   :value (provider-entry p cur)
                                   :risk (if (refs/secret-ref? (:key p)) :secret :low)
                                   :summary (str "models.yml providers." (u/kw->str id))})])))
   (common/for-tool-resources (:providers cfg) tool)))

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
          (skill-ops cfg st) (memory-ops cfg)))

(defn present? [] (some? (u/which "omp")))
