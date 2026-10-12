(ns agentctl.adapters.pi
  "pi adapter — plain JSON config files under ~/.pi/agent."
  (:require [agentctl.adapters.common :as common]
            [agentctl.extensions :as extensions]
            [agentctl.plan :as plan]
            [agentctl.refs :as refs]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.util :as u]
            [babashka.fs :as fs]))

(def tool :pi)
(def home (str u/home "/.pi/agent"))
(def settings-file (str home "/settings.json"))
(def mcp-file (str home "/mcp.json"))
(def models-file (str home "/models.json"))
(def trust-file (str home "/trust.json"))
(def skills-dir (str home "/skills"))
(def memory-file (str home "/AGENTS.md"))
(def extensions-dir (str home "/extensions"))

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

(def dialects
  "pi reads the wire format off the provider entry, so every dialect it can
   speak is one a multi-dialect provider may offer it."
  #{"anthropic-messages" "anthropic" "openai-completions" "responses"})

(defn- model-entry [m]
  (u/prune-nils
   {:id (:id m)
    :name (or (:name m) (:id m))
    :api (:api m)
    :baseUrl (:url m)
    :headers (common/resolved-headers (:headers m))}))

(defn- provider-entry [p cur d]
  (let [{:keys [status value]} (refs/resolve-ref (:key p))]
    (u/prune-nils
     {:baseUrl (:url d)
      :api (:api d)
      :headers (common/resolved-headers (:headers p))
      ;; an unresolvable ref (locked vault, unset env) must never wipe a working key
      :apiKey (if (= :ok status) value (:apiKey cur))
      :models (some->> (common/declared-models p) (mapv model-entry))})))

(defn provider-ops [cfg]
  (mapcat
   (fn [[id p]]
     (if-let [d (common/provider-dialect p dialects)]
       (let [cur (get-in (u/read-json models-file) [:providers (keyword (u/kw->str id))])
             entry (provider-entry p cur d)
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
                                     :summary (str "models.json providers." (u/kw->str id))})]))
       [(plan/op (common/no-dialect-op tool id p))]))
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

(defn- default-tool-op
  "One entry of settings.json `defaultTools`. The array is the user's as much
   as ours — `+name` entries from other extensions sit in it — so an entry is
   added or taken out by value and the rest is never rewritten."
  [id entry present?]
  (let [args {:tool tool :kind :extensions :id id :file settings-file :path [:defaultTools]}]
    (if present?
      (plan/json-array-merge-op (assoc args :value entry
                                       :summary (str "settings.json defaultTools " entry)))
      (plan/json-array-unset-op (assoc args :old entry
                                       :summary (str "settings.json defaultTools " entry " — no longer declared"))))))

(defn extension-ops
  "Link each declared extension into pi's extensions directory and switch its
   tools on. The manifest remembers which `defaultTools` entries an extension
   brought, which is the only way to take one back out later."
  [cfg st]
  (let [desired (into {} (filter (fn [[_ e]] (contains? (:for-tools e) tool))) (:extensions cfg))
        wanted (set (mapcat :default-tools (vals desired)))
        managed (into #{} (map keyword) (state/managed-ids st tool :extensions))
        dropped (fn [id] (remove wanted (:default-tools (state/entry st tool :extensions id))))]
    (concat
     (mapcat
      (fn [[id e]]
        ;; re-checked when each op runs: the fetch and the build come first in
        ;; the same plan, and either may have failed. Nothing is linked or
        ;; switched on for an extension that is not there to load
        (let [guard (fn [o what]
                      (when o
                        (assoc o :exec! (fn []
                                          (when-not (extensions/ready? e)
                                            (throw (ex-info (str "extension " (name id) " is not "
                                                                 (if (fs/directory? (:root e)) "built" "fetched")
                                                                 " — not " what)
                                                            {:root (:root e)})))
                                          ((:exec! o))))))]
          (concat
           [(some-> (plan/link-op {:tool tool :kind :extensions :id id :src (:root e)
                                   :dest (str extensions-dir "/" (name id))})
                    (assoc :entry "folder")
                    (guard "linked"))]
           (map #(guard (default-tool-op id % true) "switched on") (:default-tools e))
           (map #(default-tool-op id % false) (dropped id)))))
      (sort-by key desired))
     (mapcat
      (fn [id]
        (let [dest (str extensions-dir "/" (name id))]
          (concat
           ;; only ever a link we made; a directory there is someone's work
           [(when (fs/sym-link? dest)
              (plan/unlink-op {:tool tool :kind :extensions :id id :dest dest}))]
           (map #(default-tool-op id % false) (dropped id)))))
      (sort (remove #(contains? desired %) managed))))))

(defn plan [cfg st]
  (concat (settings-ops cfg) (mcp-ops cfg st)
          (common/project-scope-skip-ops cfg tool) (provider-ops cfg)
          (project-ops cfg) (skill-ops cfg st) (memory-ops cfg)
          (remove nil? (extension-ops cfg st))))

(defn present? [] (some? (u/which "pi")))
