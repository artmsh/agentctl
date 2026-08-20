(ns agentctl.adapters.llm
  "Simon Willison's `llm` CLI adapter.

   Providers become entries in extra-openai-models.yaml; API keys are stored in
   llm's own keys.json under a stable key name (never inlined into the YAML)."
  (:require [agentctl.adapters.common :as common]
            [agentctl.plan :as plan]
            [agentctl.refs :as refs]
            [agentctl.util :as u]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def tool :llm)

(defn user-dir []
  (or (System/getenv "LLM_USER_PATH")
      (str u/home "/Library/Application Support/io.datasette.llm")))

(defn models-file [] (str (user-dir) "/extra-openai-models.yaml"))
(defn keys-file [] (str (user-dir) "/keys.json"))
(defn aliases-file [] (str (user-dir) "/aliases.json"))
(defn default-model-file [] (str (user-dir) "/default_model.txt"))

(defn llm-sh
  "Run the llm CLI against the user dir agentctl is managing."
  [& args]
  (u/sh-env {:LLM_USER_PATH (user-dir)} args))

(defn key-name [provider-id] (str "agentctl-" (u/kw->str provider-id)))

(defn- read-models []
  (or (u/read-yaml (models-file)) []))

(defn provider-ops [cfg]
  (let [current (read-models)
        by-id (into {} (map (juxt :model_id identity)) current)]
    (mapcat
     (fn [[id p]]
       (let [models (if (vector? (:models p)) (:models p) [])
             ;; reuse whatever key entry llm already points at: renaming it would
             ;; rewrite every model line for no gain
             existing-key (some (fn [m] (:api_key_name (get by-id m))) models)
             kname (or (:key-name p) existing-key (key-name id))
             desired (mapv (fn [m]
                             (let [cur (get by-id m)]
                               (merge cur
                                      {:model_id m
                                       :model_name (or (:model_name cur) m)
                                       :api_base (:url p)
                                       :api_key_name kname}
                                      (when-not cur {:supports_tools true}))))
                           models)
             key-op (when (refs/secret-ref? (:key p))
                      (let [{:keys [status value]} (refs/resolve-ref (:key p))
                            cur (get (u/read-json (keys-file)) (keyword kname))]
                        (when (and (= :ok status) (not= cur value))
                          (plan/op {:action (if cur :update :create)
                                    :tool tool :kind :providers :id (keyword (str (name id) "/key"))
                                    :target (keys-file)
                                    :summary (str "store key as `" kname "` in keys.json")
                                    :risk :secret
                                    :diffs [{:key :api_key :before (when cur "<set>") :after "<set>"}]
                                    :exec! (fn []
                                             (u/backup! (keys-file))
                                             (let [m (or (u/read-json (keys-file)) {})]
                                               (u/write-json! (keys-file) (assoc m (keyword kname) value))))}))))
             enumerate-warn (when (= :all (:models p))
                              (plan/op {:action :noop :tool tool :kind :providers :id id
                                        :summary "llm needs explicit :models — `:models :all` cannot be enumerated offline"
                                        :warn true}))
             model-ops (keep (fn [d]
                               (let [cur (get by-id (:model_id d))]
                                 (when (not= (u/norm cur) (u/norm d))
                                   (plan/op {:action (if cur :update :create)
                                             :tool tool :kind :providers
                                             :id (keyword (str (name id) "/" (:model_id d)))
                                             :target (models-file)
                                             :summary (str "extra-openai-models.yaml " (:model_id d))
                                             :diffs (plan/field-diffs (or cur {}) d)
                                             :exec! (fn []
                                                      (u/backup! (models-file))
                                                      (let [cur-list (read-models)
                                                            others (remove #(= (:model_id %) (:model_id d)) cur-list)]
                                                        (u/write-yaml! (models-file)
                                                                       (vec (concat others [d])))))}))))
                             desired)]
         (remove nil? (concat [key-op enumerate-warn] model-ops))))
     (common/for-tool-resources (:providers cfg) tool))))

(defn settings-ops [cfg]
  (let [settings (common/settings-for cfg tool)
        want (:model settings)
        cur (some-> (u/slurp-safe (default-model-file)) str/trim)]
    (concat
     (when (and want (not= want cur))
       [(plan/op {:action (if cur :update :create)
                  :tool tool :kind :settings :id :model
                  :target (default-model-file)
                  :summary "default model"
                  :diffs [{:key :model :before cur :after want}]
                  :exec! (fn []
                           (let [{:keys [exit err]} (llm-sh "llm" "models" "default" want)]
                             (when-not (zero? exit)
                               (throw (ex-info (str "llm models default failed: " err) {})))))})])
     (for [[alias target] (:aliases settings)
           :let [cur-aliases (or (u/read-json (aliases-file)) {})
                 have (get cur-aliases (keyword (u/kw->str alias)))]
           :when (not= have target)]
       (plan/op {:action (if have :update :create)
                 :tool tool :kind :settings :id (keyword (str "alias/" (u/kw->str alias)))
                 :target (aliases-file)
                 :summary (str "alias " (u/kw->str alias) " -> " target)
                 :diffs [{:key :target :before have :after target}]
                 :exec! (fn []
                          (let [{:keys [exit err]} (llm-sh "llm" "aliases" "set" (u/kw->str alias) target)]
                            (when-not (zero? exit)
                              (throw (ex-info (str "llm aliases set failed: " err) {})))))})))))

(defn plan [cfg _st]
  (concat (settings-ops cfg) (provider-ops cfg)))

(defn present? [] (some? (u/which "llm")))
