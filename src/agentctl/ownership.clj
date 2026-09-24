(ns agentctl.ownership
  "Resolved placement records for entity DSL resources, independent of discovery."
  (:require [agentctl.config :as config]
            [agentctl.adapters.claude :as claude]
            [agentctl.adapters.codex :as codex]
            [agentctl.adapters.pi :as pi]
            [agentctl.adapters.omp :as omp]
            [agentctl.adapters.antigravity :as antigravity]
            [agentctl.scope :as scope]
            [agentctl.plan :as plan]
            [agentctl.toml :as toml]
            [agentctl.util :as u]
            [clojure.string :as str]))

(defn placements [cfg]
  (into {}
        (for [{:keys [kind id in targets decl]} (:entities cfg)
              t (let [ts (:tools decl)]
                  (if (or (nil? ts) (= :all ts))
                    (config/tools-for (case kind :trust :projects :skill-packs :skills kind))
                    (if (keyword? ts) [ts] ts)))
              p (if (= :all in) [u/home] targets)]
          [[kind id t p] {:entity-id id :tool t :kind kind :resolved-path p}])))

(defn- owners [cfg o]
  (set (for [[k p] (placements cfg)
             :when (and (= (:kind o) (:kind p)) (= (:tool o) (:tool p))
                        (= (or (:project-path o) u/home) (:resolved-path p)))] k)))

(defn write-key [o] [(:tool o) (:target o) (:path o) (when (:array-element o) (:project-path o))])

(defn- desired-fields [cfg o]
  (let [settings (if (:project o) (get-in cfg [:projects (:project o) :tools (:tool o)])
                     (get-in cfg [:tools (:tool o)]))
        mapping (case (:tool o) :claude claude/setting-keys :codex codex/setting-keys
                      :pi pi/setting-keys :antigravity antigravity/setting-keys :omp omp/setting-paths {})]
    (set (keep (fn [k] (when-let [native (get mapping k)]
                        (u/kw->str (if (vector? native) (last native) native)))) (keys settings)))))

(defn record
  "Keep the exact destinations of successful writes; planning never claims ownership."
  [st cfg failed done]
  (if-not (:entity-dsl? cfg) st
    (let [st (assoc st :placements (merge (:placements st) (placements cfg)))]
      (reduce
       (fn [s o]
         (let [triple ((juxt :tool :kind :id) o) k (write-key o)]
           (cond
             (or (failed triple) (and (some? done) (not (done triple)))) s
             (= :delete (:action o))
             (let [remaining (when (:pruned-keys o)
                               (vec (remove #(contains? (set (:pruned-keys o)) (:key %))
                                            (get-in s [:writes k :diffs]))))]
               (if (seq remaining) (assoc-in s [:writes k :diffs] remaining) (update s :writes dissoc k)))
             (and (plan/mutating? o) (:target o)
                  ;; MCPs and skills have adapter-specific prune behavior.
                  (#{:settings :permissions :trust :memory} (:kind o))
                  (seq (owners cfg o)))
             (assoc-in s [:writes k] (assoc (select-keys o [:tool :kind :id :target :path :project :project-path :fs-op :array-element])
                                           :diffs (vec (vals (into {} (map (juxt :key identity))
                                                                   (concat (get-in s [:writes k :diffs])
                                                                           (map #(select-keys % (if (= :trust (:kind o))
                                                                                                 [:key :before :after] [:key])) (:diffs o))))))
                                           :owners (owners cfg o)))
             :else s))) st (:planned-ops cfg)))))

(defn prune-ops [cfg st]
  (when (:entity-dsl? cfg)
    (keep
     (fn [[_ o]]
       (when (or (empty? (owners cfg o))
                 (and (= :settings (:kind o))
                      (some #(not ((desired-fields cfg o) (u/kw->str (:key %)))) (:diffs o)))
                 (and (= :permissions (:kind o))
                      (not (contains? (if (:project o) (get-in cfg [:projects (:project o) :permissions])
                                          (get-in cfg [:tools :claude :permissions]))
                                      (keyword (last (:path o)))))))
         (let [{:keys [tool kind id target path project project-path fs-op diffs]} o
               args {:tool tool :kind kind :id id :file target :path (mapv keyword path) :project project}
               op (cond
                    fs-op (plan/unlink-op {:tool tool :kind kind :id id :dest target :project project})
                    (:array-element o) (plan/json-array-unset-op (assoc args :old project-path))
                    (and (= :settings kind) (= "$schema" (last path))
                         (or (seq (owners cfg o))
                             (seq (owners cfg (assoc o :kind :permissions))))) nil
                    (and (= :settings kind) ((desired-fields cfg o) (last path))) nil
                    (str/ends-with? target ".json")
                    ;; Trust arrays contain unmanaged members too: subtract only
                    ;; members this write added, preserving the rest.
                    (let [{:keys [before after]} (first diffs)]
                      (if (and (= kind :trust) (vector? after))
                        (let [added (remove (set before) after)
                              current (get-in (u/read-json target) (mapv keyword path))]
                          (plan/json-set-op (assoc args :value (vec (remove (set added) current)))))
                        (plan/json-unset-op args)))
                    (str/ends-with? target ".toml")
                    (let [current (toml/read-toml target)
                          ks (remove #((desired-fields cfg o) (u/kw->str %)) (map :key diffs))
                          table (get-in current path)]
                      (when (some #(contains? table (u/kw->str %)) ks)
                        (plan/op {:tool tool :kind kind :id id :target target :path path :project project
                                  :action :delete :summary "removed from agents.edn" :pruned-keys (vec ks)
                                  :exec! #(do (u/backup! target)
                                              (toml/update-file! target
                                                                (fn [text] (reduce (fn [s k] (toml/remove-key s path k)) text ks))))})))
                    (or (str/ends-with? target ".yaml") (str/ends-with? target ".yml"))
                    (plan/yaml-remove-op args)
                    :else nil)]
           (when op (assoc op :action :delete :project-path project-path :array-element (:array-element o)
                          :ownership-prune true))))) (:writes st))))
