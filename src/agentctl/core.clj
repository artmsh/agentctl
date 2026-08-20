(ns agentctl.core
  "Planning engine: turn a normalized config into ops, execute them, track state."
  (:require [agentctl.adapters.claude :as claude]
            [agentctl.adapters.codex :as codex]
            [agentctl.adapters.common :as common]
            [agentctl.adapters.llm :as llm]
            [agentctl.adapters.omp :as omp]
            [agentctl.adapters.pi :as pi]
            [agentctl.config :as config]
            [agentctl.plan :as plan]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.util :as u]))

(def registry
  {:claude {:plan claude/plan :present? claude/present?}
   :codex  {:plan codex/plan  :present? codex/present?}
   :pi     {:plan pi/plan     :present? pi/present?}
   :omp    {:plan omp/plan    :present? omp/present?}
   :llm    {:plan llm/plan    :present? llm/present?}})

(defn- selected-tools
  "`--tool` is a direct order and outranks the config; without one the config
   decides, through the executors its projects name."
  [cfg {:keys [tools]}]
  (let [wanted (or (not-empty (set tools)) (config/active-tools cfg))]
    (filter #(and (contains? wanted %) ((get-in registry [% :present?]))) config/all-tools)))

(defn- keep-kinds [ops {:keys [kinds]}]
  (if (seq kinds) (filter #(contains? (set kinds) (:kind %)) ops) ops))

(defn- keep-projects
  "`--project` selects everything that belongs to a project, across kinds: a
   project's MCP servers are `:mcps` ops, so filtering by kind would miss them."
  [ops {:keys [projects]}]
  (if (seq projects) (filter #(contains? projects (:project %)) ops) ops))

(defn build-plan
  "All ops for the current config. Pure: touches no state on disk."
  [cfg st opts]
  (let [tool-ops (mapcat (fn [t] ((get-in registry [t :plan]) cfg st)) (selected-tools cfg opts))]
    (-> (concat (sources/pack-ops cfg) tool-ops)
        (keep-kinds opts)
        (keep-projects opts)
        vec)))

(defn missing-tools [cfg opts]
  (remove (fn [t] ((get-in registry [t :present?])))
          (or (not-empty (set (:tools opts))) (config/active-tools cfg))))

;; ---------------------------------------------------------------- inventory

(defn inventory
  "Every (tool, kind, id) the config declares — the basis for prune detection.

   MCP ids carry their scope: a project's server is owned as `:project/server`,
   so it can never be mistaken for the user-wide server of the same name and
   pruned out from under a tool that still uses it."
  [cfg]
  (filter
   (comp (set (config/active-tools cfg)) first)
   (concat
   (for [[id m] (:mcps cfg) t (:tools m) :when (= :global (:scope m))] [t :mcps id])
   (for [[pid proj] (:projects cfg)
         mid (:mcp proj)
         :let [m (get-in cfg [:mcps mid])]
         :when (and m (common/project-scopes (:scope m)))
         t (:tools m)
         :when (contains? common/project-mcp-tools t)]
     [t :mcps (keyword (name pid) (name mid))])
   ;; a project's skill is owned user-wide only by tools that cannot host one
   ;; inside a project — for them the user's home is the only place it fits
   (for [[id s] (sources/all-skills cfg) t (:tools s)
         :when (or (= :global (:scope s))
                   (not (contains? common/project-skill-tools t)))]
     [t :skills id])
   ;; a project's skills are owned under the project, so the user-wide skill of
   ;; the same name is never pruned on their behalf
   (for [[pid proj] (:projects cfg)
         :let [ts (filter (:for-tools proj) common/project-skill-tools)]
         [sid _] (:skills (sources/project-skills cfg proj))
         t ts]
     [t :skills (keyword (name pid) (name sid))])
   (for [[id p] (:providers cfg) t (:tools p)] [t :providers id])
   (for [[id m] (:memory cfg) t (:tools m)] [t :memory id])
   (for [[id _] (:projects cfg) t [:claude :codex :pi]] [t :projects id]))))

(defn sync-state!
  "Record what we now own; forget resources dropped from the config.
   `failed` (tool kind id) triples are left unrecorded — claiming ownership of
   something we never managed to create would make a later prune delete it."
  ([st cfg] (sync-state! st cfg #{}))
  ([st cfg failed]
   (let [owned (remove (fn [[t k id]] (contains? failed [t k id])) (inventory cfg))
         want (set (map (fn [[t k id]] (state/key-for t k id)) owned))
         stale (remove want (set (keys (:managed st))))]
     (as-> st $
       (reduce (fn [s [t k id]] (state/record s t k id {})) $ owned)
       (reduce (fn [s key] (update s :managed dissoc key)) $ stale)))))

;; ---------------------------------------------------------------- apply

(defn- fetched-sources?
  "Did this pass put new source directories on disk? A pack's skills cannot be
   enumerated before the pack exists."
  [done]
  (boolean (some #(= :skill-packs (:kind %)) done)))

(defn execute!
  "Run every mutating op. Returns {:done [...] :failed [...]}."
  [ops]
  (reduce (fn [acc o]
            (if-not (plan/mutating? o)
              acc
              (try
                ((:exec! o))
                (update acc :done conj o)
                (catch Exception e
                  (update acc :failed conj (assoc o :error (.getMessage e)))))))
          {:done [] :failed []}
          ops))

(defn converge!
  "Run the plan, then re-plan and run again if the first pass fetched a pack.
   The skills inside a pack are invisible until it is cloned, so a single pass
   would leave a fresh clone unlinked until the next run."
  [cfg st opts ops]
  (let [{:keys [done failed]} (execute! ops)]
    (if-not (fetched-sources? done)
      {:done done :failed failed}
      (let [{d2 :done f2 :failed} (execute! (build-plan cfg st opts))]
        {:done (concat done d2) :failed (concat failed f2)}))))
