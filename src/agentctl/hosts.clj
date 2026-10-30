(ns agentctl.hosts
  "Planning and converging a remote host declared under `:hosts`.

   The adapters cannot serve here: their paths are bound from the local home at
   load time and they read through the local filesystem. So a host gets its own
   small planner over `remote`'s primitives, which emits ordinary ops — the
   renderer, the summary and `execute!` neither know nor care where they land.

   A remote copy has no link back to its source, so drift is a comparison of
   per-file byte hashes. Ownership lives in one state file per host, apart from
   `state.edn`: nothing a host does can make a local run think something was
   dropped from the config and prune it."
  (:require [agentctl.config :as config]
            [agentctl.core :as core]
            [agentctl.plan :as plan]
            [agentctl.remote :as remote]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

(def supported-tools
  "Tools a host can be provisioned for so far."
  #{:codex})

(def supported-kinds #{:skills :skill-packs})

(defn lookup
  "The host `--host` names, or an error that lists what is declared."
  [cfg id]
  (or (get-in cfg [:hosts id])
      (throw (ex-info (str "no host " (name id) " under :hosts (declared: "
                           (if-let [hs (seq (keys (:hosts cfg)))]
                             (str/join ", " (sort (map name hs)))
                             "none")
                           ")")
                      {:host id}))))

;; ---------------------------------------------------------------- local side

(defn- sha256-bytes [^bytes bs]
  (->> (.digest (MessageDigest/getInstance "SHA-256") bs)
       (map #(format "%02x" %))
       (apply str)))

(defn- not-shipped?
  "VCS metadata, bytecode caches and Finder litter are not part of a skill, on
   either side: running a skill's script here must not read as drift there."
  [rel]
  (let [segs (str/split rel #"/")]
    (or (some #{".git" "__pycache__"} segs) (= ".DS_Store" (last segs)))))

(defn source-files
  "The tree under `dir` as the host should hold it. Links are followed: the far
   side gets what they point at."
  [dir]
  (vec (for [f (sort (map str (fs/glob dir "**" {:hidden true :follow-links true})))
             :when (fs/regular-file? f)
             :let [rel (str/replace (str (fs/relativize dir f)) "\\" "/")]
             :when (not (not-shipped? rel))
             :let [bs (fs/read-all-bytes f)]]
         {:rel rel :bytes bs :sha (sha256-bytes bs) :exec? (fs/executable? f)})))

(defn- manifest [files] (into {} (map (juxt :rel :sha)) files))

(defn- shipped-manifest [m] (into {} (remove (comp not-shipped? key)) m))

(defn- file-diffs
  "Which files differ, by name: a hash pair says nothing a reader can act on,
   so the values are a short prefix of each."
  [have want]
  (let [short #(some-> % (subs 0 8))]
    (vec (for [rel (sort (distinct (concat (keys have) (keys want))))
               :let [b (get have rel) a (get want rel)]
               :when (not= b a)]
           {:key rel :before (short b) :after (short a)}))))

(defn- safe-id?
  "An id becomes one path segment on a filesystem that may be Windows'."
  [id]
  (and (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" id)
       (not (str/ends-with? id "."))))

;; ---------------------------------------------------------------- plan

(defn- declared-tools [hcfg]
  (set (concat (mapcat (comp :tools val) (:skills hcfg))
               (mapcat (comp :tools val) (:skill-packs hcfg)))))

(defn- warn-op [tool kind id summary]
  (plan/op {:tool tool :kind kind :id id :warn true :summary summary}))

(defn- placeable? [host src]
  (or (not (remote/windows? host))
      (every? (comp remote/windows-safe-rel? :rel) (source-files src))))

(defn- skill-op
  "A no-op when the host already holds exactly what the source holds."
  [host {:keys [root entries]} id src owned?]
  (let [{:keys [state files]} (get entries (name id))
        files (shipped-manifest files)
        target (str root (remote/separator host) (name id))
        source (source-files src)
        want (manifest source)
        unplaceable (when (remote/windows? host) (first (remove remote/windows-safe-rel? (keys want))))]
    (cond
      unplaceable
      (warn-op :codex :skills id (str "file name cannot exist on Windows: " unplaceable " — not installed"))

      (and (= :dir state) (= files want))
      (plan/op {:tool :codex :kind :skills :id id :target target})

      :else
      (let [exists? (not= :absent (or state :absent))]
        (plan/op {:action (if exists? :update :create)
                  :tool :codex :kind :skills :id id
                  :host (name (:id host))
                  :target target
                  :category :fs :entry "folder" :fs-op "copy"
                  :from (u/tilde src)
                  :summary (str "copy " (u/tilde src) " -> " target)
                  :note (when (and exists? (not owned?))
                          "not owned by agentctl — backed up on the host, then replaced")
                  :diffs (if (= :dir state)
                           (file-diffs files want)
                           [{:key :target
                             :before (case state :link "<link>" :file "<file>" nil)
                             :after "copy"}])
                  :risk (if exists? :medium :low)
                  ;; re-read: a re-plan after a pack fetch may reach this op
                  ;; long after the plan hashed the tree
                  :exec! (fn [] (remote/write-tree! host (name id) (source-files src)))})))))

(defn plan-host
  "`{:ops :desired :gone :skipped :inventory}`. Always talks to the host, even
   when nothing would be planned: an unreachable one has to surface as such
   rather than as an empty plan. `:desired` is what a successful run owns,
   `:gone` what state can forget because the host no longer has it."
  [host st {:keys [tools kinds]}]
  (let [hcfg (:cfg host)
        kind? #(or (empty? kinds) (contains? (set kinds) %))
        selected? #(or (empty? tools) (contains? (set tools) %))
        wanted (filter selected? (declared-tools hcfg))
        probe (filter supported-tools wanted)
        managing? (and (some #{:codex} wanted) (kind? :skills))
        ;; a pack not on disk has unknown contents, so its skills are neither
        ;; desired nor undesired: any prune decision would be a guess
        unfetched (for [[id p] (:skill-packs hcfg) :when (not (sources/materialized? p))] id)
        skills (when managing? (sources/for-tool hcfg (sources/all-skills hcfg) :codex))
        {valid true invalid false} (group-by (comp boolean safe-id? name key) skills)
        owned (when managing? (map keyword (state/managed-ids st :codex :skills)))
        desired-ids (map key valid)
        prunable (when (empty? unfetched) (remove (set desired-ids) owned))
        inv (remote/inventory host probe (sort (distinct (map name (concat desired-ids prunable)))))
        absent (remove (:tools inv) probe)
        present? (contains? (:tools inv) :codex)
        resolved (when present? (filter (fn [[_ s]] (:source s)) valid))
        present-at (fn [id] (not= :absent (get-in inv [:entries (name id) :state] :absent)))]
    {:inventory inv
     :skipped (vec absent)
     :desired (into {} (for [[id s] resolved
                             :when (and (u/exists? (str (:source s) "/SKILL.md"))
                                        (placeable? host (:source s)))]
                         [id {:source (:source s)
                              :target (str (:root inv) (remote/separator host) (name id))}]))
     :gone (when present? (set (remove present-at prunable)))
     :ops
     (vec
      (concat
       (when (kind? :skill-packs) (sources/pack-ops hcfg))
       (for [k (remove supported-kinds kinds)]
         (warn-op :agentctl k (keyword "host" (name k))
                  (str (name k) " are not provisioned on hosts yet — only skills are")))
       (for [t (sort-by name (remove supported-tools wanted))]
         (warn-op t :skills (keyword "host" (name t))
                  (str (name t) " skills are not provisioned on hosts yet — only codex's are")))
       (for [k (:unsupported host)]
         (warn-op :agentctl k (keyword "host" (name k))
                  (str ":hosts " (name (:id host)) " declares " (name k)
                       ", which hosts do not support yet — ignored")))
       (for [id unfetched :when (or managing? (kind? :skill-packs))]
         (warn-op :agentctl :skill-packs id
                  "pack is not on disk yet — its skills are not planned, nothing is pruned"))
       (for [[id _] invalid]
         (warn-op :codex :skills id "a host needs an id of ASCII letters, digits, ., _ and - — not installed"))
       (for [[id s] (when present? valid)
             :when (not (u/exists? (str (:source s) "/SKILL.md")))]
         (warn-op :codex :skills id (str "no SKILL.md in " (or (some-> (:source s) u/tilde)
                                                               "an unresolved source")
                                         " — not installed")))
       (for [[id s] resolved
             :when (u/exists? (str (:source s) "/SKILL.md"))]
         (skill-op host inv id (:source s) (state/managed? st :codex :skills id)))
       (for [id prunable :when (and present? (present-at id))]
         (plan/op {:action :delete :tool :codex :kind :skills :id id
                   :host (name (:id host))
                   :target (str (:root inv) (remote/separator host) (name id))
                   :category :fs :entry "folder" :fs-op "remove"
                   :summary "removed from agents.edn — moved to the host's backups"
                   :risk :medium
                   :exec! (fn [] (remote/park! host (name id)))}))))}))

;; ---------------------------------------------------------------- state

(defn sync-host-state
  "Own what is now converged, forget what is now gone. A failed write stays
   unrecorded: a later prune must never delete what it never managed to create."
  [st {:keys [desired gone]} done failed]
  (let [bad (set (mapcat core/op-keys failed))
        removed (for [o done :when (= :delete (:action o))] (:id o))]
    (as-> st $
      (reduce (fn [s [id {:keys [source target]}]]
                (if (bad [:codex :skills id])
                  s
                  (state/record s :codex :skills id {:source source :resolved-path target})))
              $ desired)
      (reduce #(state/forget %1 :codex :skills %2) $ (concat gone removed)))))

;; ---------------------------------------------------------------- converge

(defn converge!
  "Run the plan; if it fetched a pack, re-plan and run again, because a pack's
   skills cannot be enumerated before the pack exists. Returns the last plan
   along with everything done and failed."
  [cfg host st opts {:keys [ops] :as planned}]
  (let [{:keys [done failed]} (core/execute! ops)]
    (if-not (some #(= :skill-packs (:kind %)) done)
      {:plan planned :done done :failed failed}
      ;; `:entity-raw`, not `:raw`: an entity config's `:raw` is its lowered form,
      ;; which no longer holds `:hosts`
      (let [host (get-in (config/normalize (or (:entity-raw cfg) (:raw cfg)) (:source cfg))
                         [:hosts (:id host)])
            again (plan-host host st opts)
            {d2 :done f2 :failed} (core/execute! (:ops again))]
        {:plan again :done (concat done d2) :failed (concat failed f2)}))))

;; ---------------------------------------------------------------- validate

(defn check-host
  "Findings for one host, as `[level where message]`. A host that cannot answer
   is `:unknown`: nothing about it was checked, so nothing about it is clean."
  [host st opts]
  (let [where [:hosts (:id host)]]
    (try
      (let [{:keys [ops skipped inventory]} (plan-host host st opts)
            changes (filter plan/mutating? ops)]
        (concat
         [[:ok where (str "reachable over ssh — " (:ssh host) ", " (name (:os host))
                          ", home " (:home inventory))]]
         (for [t skipped]
           [:warn where (str (name t) " is not installed on this host — skipped")])
         (for [o ops :when (:warn o)]
           [:warn (conj where (:kind o) (:id o)) (:summary o)])
         [(if (seq changes)
            [:warn where (str (count changes) " pending change(s) — run `agentctl apply --host "
                              (name (:id host)) "`")]
            [:ok where "host matches agents.edn"])]))
      (catch clojure.lang.ExceptionInfo e
        (if (:remote (ex-data e))
          [[:unknown where (ex-message e)]]
          (throw e))))))
