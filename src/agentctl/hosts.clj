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
  (:require [agentctl.adapters.codex :as codex]
            [agentctl.adapters.common :as common]
            [agentctl.config :as config]
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
  "Tools a host can be provisioned for so far, each with a skills root of its
   own (`remote/roots`): codex's `$CODEX_HOME/skills`, and `~/.agents/skills`
   for awl, which reads it in place of the agents.edn a host does not have."
  #{:codex :awl})

(def ^:private probed
  "Tools whose CLI must be on the host for anything to be placed for them. awl
   is not asked: it is a shell alias, or off a non-interactive PATH, as often
   as not, and `~/.agents/skills` is a root several CLIs read, not awl's alone."
  #{:codex})

(def codex-reads-agents-since
  "The first codex to read `~/.agents/skills` as a user root, beside its own
   `$CODEX_HOME/skills`. It lists a skill once per copy, so a skill copied into
   both roots is listed twice."
  [0 95 0])

(defn codex-reads-agents?
  "Does the codex whose `--version` printed `line` read `~/.agents/skills`?
   nil when the line carries no codex version: a wrapper's error names its
   runtime's (`Node.js v18.19.1`), which says nothing about codex. A
   pre-release comes before its release, so `0.95.0-alpha.2` reads as older."
  [line]
  (when-let [[_ a b c pre] (some->> line (re-find #"\bcodex-cli\s+v?(\d+)\.(\d+)\.(\d+)(-\S+)?"))]
    (let [order (compare (mapv parse-long [a b c]) codex-reads-agents-since)]
      (or (pos? order) (and (zero? order) (nil? pre))))))

(defn roots-for
  "The roots one skill is copied into, given the tools that want it and are on
   the host. `~/.agents/skills` serves a codex that reads it, so a skill both
   want lands there alone; unless codex is known to read it, both get a copy."
  [wants shared?]
  (cond-> []
    (and (wants :codex) (not (and shared? (wants :awl)))) (conj :codex)
    (wants :awl) (conj :awl)))

(defn- serves
  "The tools that read `root`, which is what `-t` selects a root's ops by."
  [root shared?]
  (case root :codex #{:codex} :awl (if shared? #{:awl :codex} #{:awl})))

(def supported-kinds #{:skills :skill-packs :settings})

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

(defn- local-copy
  "A host's file as a local file the adapters' ops can edit: they read and
   write through the local filesystem. No file there, none here."
  [{:keys [bytes]}]
  (let [dir (fs/create-temp-dir {:prefix "agentctl-host-"})
        f (str (fs/path dir "config.toml"))]
    (fs/delete-on-exit dir)
    (when bytes (fs/write-bytes f bytes))
    (fs/delete-on-exit f)
    f))

(defn- settings-ops
  "Codex's settings ops, planned against a local copy of the host's
   config.toml and shown at the host's path. They edit the copy; `push!`
   sends it back once they have run."
  [host hcfg st remote-file local]
  (for [o (codex/settings-ops hcfg st local)]
    (cond-> (assoc o :host (name (:id host)))
      (= local (:target o)) (assoc :target (:path remote-file))
      (plan/mutating? o) (assoc :host-file :codex-config))))

(defn- explicit-settings-tools
  "Tools a host's settings row names. A row naming none is read as codex's:
   the others are not provisioned on hosts, and would each warn."
  [hcfg]
  (set (for [e (:entities hcfg) :when (= :settings (:kind e))
             t (let [ts (get-in e [:decl :tools])] (if (keyword? ts) [ts] ts))
             :when (and t (not= :all t))]
         t)))

(defn- tools-of [s] (into (set (:tools s)) (:consumers s)))

(defn- declared-tools [hcfg]
  (set (mapcat (comp tools-of val) (concat (:skills hcfg) (:skill-packs hcfg)))))

(defn- warn-op [tool kind id summary]
  (plan/op {:tool tool :kind kind :id id :warn true :summary summary}))

(defn- placeable? [host src]
  (or (not (remote/windows? host))
      (every? (comp remote/windows-safe-rel? :rel) (source-files src))))

(defn- target-in [host inv root id]
  (str (get-in inv [:roots root]) (remote/separator host) (name id)))

(defn- skill-op
  "A no-op when the host already holds exactly what the source holds."
  [host inv root id src owned? also]
  (let [{:keys [state files]} (get-in inv [:entries root (name id)])
        files (shipped-manifest files)
        target (target-in host inv root id)
        source (source-files src)
        want (manifest source)
        unplaceable (when (remote/windows? host) (first (remove remote/windows-safe-rel? (keys want))))]
    (cond
      unplaceable
      (warn-op root :skills id (str "file name cannot exist on Windows: " unplaceable " — not installed"))

      (and (= :dir state) (= files want))
      (plan/op {:tool root :kind :skills :id id :target target})

      :else
      (let [exists? (not= :absent (or state :absent))]
        (plan/op {:action (if exists? :update :create)
                  :tool root :kind :skills :id id
                  :host (name (:id host))
                  :target target
                  :category :fs :entry "folder" :fs-op "copy"
                  :from (u/tilde src)
                  :summary (str "copy " (u/tilde src) " -> " target
                                (when also (str " — " (name also) " reads it there too")))
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
                  :exec! (fn [] (remote/write-tree! host root (name id) (source-files src)))})))))

(defn- source-in
  "Where a skill's copy in `root` comes from: codex honours a per-tool path."
  [hcfg id s root]
  (if (= :codex root)
    (:source (get (sources/for-tool hcfg {id s} :codex) id))
    (:source s)))

(defn plan-host
  "`{:ops :desired :gone :skipped :inventory}`. Always talks to the host, even
   when nothing would be planned: an unreachable one has to surface as such
   rather than as an empty plan. `:desired` is what a successful run owns and
   `:gone` what state can forget because the host no longer has it, both keyed
   by `[root id]`: one id can be owned in two roots at once.

   Where a skill goes depends on the host's codex (`roots-for`), so codex is
   asked whatever `-t` says; `-t` then selects ops by the tools that read the
   root they act in."
  [host st {:keys [tools kinds] :as opts}]
  (let [hcfg (:cfg host)
        ;; as locally: `-k skills` keeps the fetch of a pack its skills come from
        kind? #(core/kind-selected? opts %)
        selected? #(or (empty? tools) (contains? (set tools) %))
        ;; with its last skill dropped from agents.edn, a tool is declared
        ;; nowhere, and still has that skill on the host to prune
        codex-settings (common/settings-for hcfg :codex)
        declared (cond-> (into (declared-tools hcfg) (filter #(seq (state/managed-ids st % :skills)) remote/roots))
                   (seq codex-settings) (conj :codex))
        settings? (and (kind? :settings) (selected? :codex) (boolean (seq codex-settings)))
        wanted (filter selected? declared)
        probe (filter probed declared)
        managing? (and (kind? :skills) (boolean (some supported-tools wanted)))
        ;; a pack not on disk has unknown contents, so its skills are neither
        ;; desired nor undesired: any prune decision would be a guess
        unfetched (for [[id p] (:skill-packs hcfg) :when (not (sources/materialized? p))] id)
        skills (when managing?
                 (into {} (for [[id s] (sources/all-skills hcfg)
                                :let [w (set (filter supported-tools (tools-of s)))]
                                :when (seq w)]
                            [id (assoc s :wants w)])))
        {valid true invalid false} (group-by (comp boolean safe-id? name key) skills)
        owned (when managing?
                (set (for [r remote/roots id (state/managed-ids st r :skills)] [r (keyword id)])))
        inv (remote/inventory host probe (sort (distinct (map name (concat (keys valid) (map second owned)))))
                              (when settings? [:codex-config]))
        there (into (set (remove probed supported-tools)) (:tools inv))
        shared? (when (there :codex) (codex-reads-agents? (get-in inv [:versions :codex])))
        root-on? (fn [r] (and (there r) (boolean (some selected? (serves r shared?)))))
        placed (for [[id s] valid
                     r (roots-for (set (filter there (:wants s))) shared?)
                     :when (root-on? r)]
                 {:root r :id id :wants (:wants s) :source (source-in hcfg id s r)})
        ready? #(u/exists? (str (:source %) "/SKILL.md"))
        placed-at (group-by :id placed)
        ;; a skill moving to another root leaves the old copy until the new one
        ;; can be written: a broken source must not cost it both
        prunable (when (empty? unfetched)
                   (sort (for [[r id :as k] owned
                               :let [ps (placed-at id)]
                               ;; a codex copy beside the shared one is a duplicate
                               ;; that placing the shared one made: -t awl undoes it
                               :when (and (or (root-on? r)
                                              (and shared? (= :codex r) (some #(= :awl (:root %)) ps)))
                                          (not (some #(= r (:root %)) ps))
                                          (every? #(and (ready? %) (placeable? host (:source %))) ps))]
                           k)))
        state-at (fn [r id] (get-in inv [:entries r (name id) :state] :absent))
        present-at (fn [[r id]] (not= :absent (state-at r id)))
        ;; and a write that fails at run time keeps it too
        written (atom #{})
        place-ops (for [{:keys [root id wants source] :as p} placed :when (ready? p)
                        :let [o (skill-op host inv root id source (state/managed? st root :skills id)
                                          (when (and (= :awl root) shared? (wants :codex)) :codex))]]
                    (cond-> o
                      (plan/mutating? o)
                      (update :exec! (fn [f] (fn [] (f) (swap! written conj [root id]))))))
        pending (set (for [o place-ops :when (plan/mutating? o)] [(:tool o) (:id o)]))
        remote-config (get-in inv [:host-files :codex-config])
        local-config (when (and settings? ((:tools inv) :codex)) (local-copy remote-config))]
    {:inventory inv
     :skipped (vec (filter selected? (remove (:tools inv) probe)))
     :desired (into {} (for [{:keys [root id source] :as p} placed
                             :when (and (ready? p) (placeable? host source))]
                         [[root id] {:source source :target (target-in host inv root id)}]))
     :gone (set (remove present-at prunable))
     :push (when local-config
             {:file :codex-config :local local-config
              :expected (some-> (:bytes remote-config) remote/sha256)})
     :ops
     (vec
      (concat
       (when (kind? :skill-packs) (sources/pack-ops hcfg))
       (for [t (sort-by name (remove #{:codex} (explicit-settings-tools hcfg)))
             :when (and (kind? :settings) (selected? t))]
         (warn-op t :settings (keyword "host" (name t))
                  (str (name t) " settings are not provisioned on hosts yet — only codex's are")))
       (when local-config (settings-ops host hcfg st remote-config local-config))
       (for [k (remove supported-kinds kinds)]
         (warn-op :agentctl k (keyword "host" (name k))
                  (str (name k) " are not provisioned on hosts yet — only skills are")))
       (for [t (sort-by name (remove supported-tools wanted))]
         (warn-op t :skills (keyword "host" (name t))
                  (str (name t) " skills are not provisioned on hosts yet — only codex's and awl's are")))
       (for [k (:unsupported host)]
         (warn-op :agentctl k (keyword "host" (name k))
                  (str ":hosts " (name (:id host)) " declares " (name k)
                       ", which hosts do not support yet — ignored")))
       (for [id unfetched :when (or managing? (kind? :skill-packs))]
         (warn-op :agentctl :skill-packs id
                  "pack is not on disk yet — its skills are not planned, nothing is pruned"))
       (for [[id s] invalid]
         (warn-op (first (sort-by name (:wants s))) :skills id
                  "a host needs an id of ASCII letters, digits, ., _ and - — not installed"))
       (when (and (there :codex) (nil? shared?) (some #(every? (:wants %) [:codex :awl]) placed))
         [(warn-op :codex :skills :host/codex-version
                   (str "codex --version gave no version (" (pr-str (get-in inv [:versions :codex] ""))
                        "), so whether it reads ~/.agents/skills is unknown — a skill both codex and awl"
                        " want is copied into both roots, and codex may list it twice"))])
       (for [[id ps] placed-at
             :let [p (first ps)]
             :when (not (ready? p))]
         (warn-op (:root p) :skills id (str "no SKILL.md in " (or (some-> (:source p) u/tilde)
                                                                  "an unresolved source")
                                            " — not installed")))
       ;; codex reads both roots, and a hand-made copy in the other one is
       ;; listed beside agentctl's: the duplicate this placement avoids itself
       (for [{:keys [root id] :as p} placed
             :let [other (first (remove #{root} remote/roots))]
             :when (and shared? (ready? p)
                        (= :dir (state-at other id))
                        (not (state/managed? st other :skills id)))]
         (warn-op root :skills id (str "codex reads " (target-in host inv other id)
                                       " too, which agentctl does not own — it lists " (name id) " twice")))
       place-ops
       (for [[r id :as k] prunable :when (present-at k)
             :let [now (some #(when (not= r (:root %)) (target-in host inv (:root %) id)) (placed-at id))]]
         (plan/op {:action :delete :tool r :kind :skills :id id
                   :host (name (:id host))
                   :target (target-in host inv r id)
                   :category :fs :entry "folder" :fs-op "remove"
                   :summary (if now
                              (str "placed in " now " instead — moved to the host's backups")
                              "removed from agents.edn — moved to the host's backups")
                   :risk :medium
                   :exec! (fn []
                            (when (some #(and (pending %) (not (@written %)))
                                        (for [p (placed-at id)] [(:root p) id]))
                              (throw (ex-info (str "kept: its copy in " now " was not written") {:id id})))
                            (remote/park! host r (name id)))}))))}))

;; ---------------------------------------------------------------- state

(defn sync-host-state
  "Own what is now converged, forget what is now gone. A failed write stays
   unrecorded: a later prune must never delete what it never managed to create."
  [st {:keys [desired gone]} done failed]
  (let [bad (set (mapcat core/op-keys failed))
        removed (for [o done :when (= :delete (:action o))] [(:tool o) (:id o)])]
    (as-> st $
      (reduce (fn [s [[root id] {:keys [source target]}]]
                (if (bad [root :skills id])
                  s
                  (state/record s root :skills id {:source source :resolved-path target})))
              $ desired)
      (reduce (fn [s [root id]] (state/forget s root :skills id)) $ (concat gone removed)))))

;; ---------------------------------------------------------------- converge

(defn- push!
  "Send the edited copy of a host file back, if any op edited it. The write
   checks the host still holds what the plan read; when it fails, so does
   every edit it carried."
  [host {:keys [push]} {:keys [done failed] :as ran}]
  (let [edited (filter :host-file done)]
    (if (or (nil? push) (empty? edited))
      ran
      (try
        (remote/write-file! host (:file push) (fs/read-all-bytes (:local push)) (:expected push))
        ran
        (catch clojure.lang.ExceptionInfo e
          {:done (vec (remove :host-file done))
           :failed (into (vec failed) (map #(assoc % :error (ex-message e))) edited)})))))

(defn converge!
  "Run the plan; if it fetched a pack, re-plan and run again, because a pack's
   skills cannot be enumerated before the pack exists. Returns the last plan
   along with everything done and failed."
  [cfg host st opts {:keys [ops] :as planned}]
  (let [{:keys [done failed]} (push! host planned (core/execute! ops))]
    (if-not (some #(= :skill-packs (:kind %)) done)
      {:plan planned :done done :failed failed}
      ;; `:entity-raw`, not `:raw`: an entity config's `:raw` is its lowered form,
      ;; which no longer holds `:hosts`
      (let [host (get-in (config/normalize (or (:entity-raw cfg) (:raw cfg)) (:source cfg))
                         [:hosts (:id host)])
            again (plan-host host st opts)
            {d2 :done f2 :failed} (push! host again (core/execute! (:ops again)))]
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
