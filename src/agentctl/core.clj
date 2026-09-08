(ns agentctl.core
  "Planning engine: turn a normalized config into ops, execute them, track state."
  (:require [agentctl.adapters.antigravity :as antigravity]
            [agentctl.adapters.claude :as claude]
            [agentctl.adapters.codex :as codex]
            [agentctl.adapters.common :as common]
            [agentctl.adapters.llm :as llm]
            [agentctl.adapters.omp :as omp]
            [agentctl.adapters.pi :as pi]
            [agentctl.config :as config]
            [agentctl.plan :as plan]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def registry
  {:claude {:plan claude/plan :present? claude/present?}
   :codex  {:plan codex/plan  :present? codex/present?}
   :pi     {:plan pi/plan     :present? pi/present?}
   :omp    {:plan omp/plan    :present? omp/present?}
   :llm    {:plan llm/plan    :present? llm/present?}
   :antigravity {:plan antigravity/plan :present? antigravity/present?}})

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
   project's MCP servers are `:mcps` ops, so filtering by kind would miss them.

   Skill-pack ops are the exception that has to be let through by hand. A pack
   is fetched once for the whole machine, so its op carries no `:project` at
   all — dropping it would leave a project asking for skills out of a pack
   nothing on this run is allowed to clone, and `converge!` would never get
   the second pass that links what a clone brought."
  [ops cfg {:keys [projects]}]
  (if (empty? projects)
    ops
    (let [packs (sources/packs-for cfg projects)]
      (filter #(or (contains? projects (:project %))
                   (and (= :skill-packs (:kind %)) (contains? packs (:id %))))
              ops))))

(defn- under?
  "Is `child` `parent` itself or somewhere beneath it? Compared on whole path
   segments — ~/projects/agent is not a parent of ~/projects/agentctl."
  [parent child]
  (or (= parent child) (str/starts-with? child (str parent "/"))))

(defn- canonical
  "`real-path` can only resolve a path that exists, and a project is often
   declared before its directory is. Resolve the deepest ancestor that does
   exist and keep the rest as written — otherwise on macOS a cwd of /tmp comes
   back as /private/tmp while a not-yet-created project under it does not, and
   the two never compare equal."
  [p]
  (let [p (u/abs-path p)]
    (loop [cur p tail ()]
      (cond
        (u/exists? cur) (str/join "/" (cons (u/real-path cur) tail))
        (nil? (fs/parent cur)) p
        :else (recur (str (fs/parent cur)) (conj tail (str (fs/file-name cur))))))))

(defn- segments [p] (vec (remove str/blank? (str/split (str p) #"/"))))

(defn- common-ancestor
  "Deepest directory every one of `paths` lies under."
  [paths]
  (when (seq paths)
    (str "/" (str/join "/" (->> (apply map vector (map segments paths))
                                (take-while (partial apply =))
                                (map first))))))

(defn- workspace-root
  "The directory the declared projects are filed under — the deepest one their
   parents share. The root has to be where the projects themselves are kept, or
   `apply` from anywhere above would quietly stop planning the whole config."
  [paths]
  (common-ancestor (map #(str (fs/parent %)) paths)))

(def ^:private not-a-workspace
  "Home and the filesystem root are where everything lives, so they say nothing
   about projects. Projects spread across `~/projects` and `~/dotfiles` make
   home their common parent by arithmetic; treating that as a workspace would
   turn every bare `apply` from home into a run that skips the whole global
   half of the config without being asked to."
  #{(u/real-path u/home) "/"})

(defn cwd-scope
  "Which projects a bare `apply` run from `cwd` should narrow itself to, or
   nil for no narrowing at all.

   Standing inside a declared project means that project — the deepest one, so
   a project nested under another still wins. Standing on the workspace the
   projects are filed under, or on a directory inside it, means every project
   below where you stand. Anywhere else is not a question about a project, so
   the whole config applies, exactly as it did before."
  [cfg cwd]
  (let [cwd (canonical cwd)
        paths (for [[id proj] (:projects cfg)] [id (canonical (:path proj))])
        inside (->> paths
                    (filter (fn [[_ p]] (under? p cwd)))
                    (sort-by (comp count second))
                    last)]
    (if inside
      {:projects #{(first inside)} :where :project :path (second inside)}
      (let [root (workspace-root (map second paths))
            children (when (and root (under? root cwd) (not (not-a-workspace cwd)))
                       (->> paths (filter (fn [[_ p]] (under? cwd p))) (map first) set))]
        (when (seq children)
          {:projects children :where :root :path cwd})))))

(defn scoped?
  "Did `-t`/`-k`/`-p` narrow this run to less than the whole config? Callers
   use this to decide what `done` to pass `sync-state!` — see its docstring."
  [{:keys [tools kinds projects]}]
  (boolean (or (seq tools) (seq kinds) (seq projects))))

(defn build-plan
  "All ops for the current config. Pure: touches no state on disk."
  [cfg st opts]
  (let [tool-ops (mapcat (fn [t] ((get-in registry [t :plan]) cfg st)) (selected-tools cfg opts))]
    (-> (concat (sources/pack-ops cfg) tool-ops)
        (keep-kinds opts)
        (keep-projects cfg opts)
        vec)))

(defn missing-tools [cfg opts]
  (remove (fn [t] ((get-in registry [t :present?])))
          (or (not-empty (set (:tools opts))) (config/active-tools cfg))))

;; ---------------------------------------------------------------- inventory

(defn inventory
  "Every (tool, kind, id) the config declares — the basis for prune detection.
   A tuple may carry a fourth element, `data`, merged into that resource's
   state-manifest entry; every kind but one leaves it off and is looked up by
   id alone (an object keyed by name, a symlink at a fixed path). Hooks are
   the exception: a JSON array has no key of its own, so `hook-ops` needs the
   exact element it wrote last time (`:path`/`:value`) handed back to it, and
   the manifest is the only place that survives between runs.

   MCP ids carry their scope: a project's server is owned as `:project/server`,
   so it can never be mistaken for the user-wide server of the same name and
   pruned out from under a tool that still uses it."
  [cfg]
  (filter
   (comp (set (config/active-tools cfg)) first)
   (concat
   (for [[id decl] (:hooks (get-in cfg [:tools :claude]))
         :let [path [:hooks (keyword (u/kw->str (:event decl)))]]]
     [:claude :hooks id {:path path :value (claude/hook-group decl)}])
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
   (for [[id _] (:projects cfg) t (config/tools-for :projects)] [t :projects id]))))

(defn sync-state!
  "Record what we now own; forget resources dropped from the config.
   `failed` (tool kind id) triples are left unrecorded — claiming ownership of
   something we never managed to create would make a later prune delete it.

   `done` (tool kind id) triples are the ops a *scoped* run (`-t`/`-k`/`-p`)
   actually executed — pass nil, not `#{}`, for an unfiltered run. Most
   inventory tuples carry no `data` (an id is enough to say ownership), but a
   few — hooks' `:path`/`:value` — carry the exact value written to disk. On
   an unfiltered run a hook with no op is positive evidence the declared
   value already matches disk (that's what `present?` just checked), so
   refreshing the stored value is correct and `done` is nil to allow it. A
   scoped run instead may simply never have planned that id at all; there,
   refreshing on nothing-happened evidence would claim a value is live that
   this run never wrote, and the next unfiltered run would then fail to find
   the real element to replace, orphaning it in the array forever — so there,
   only ids actually in `done` refresh, everything else keeps what the
   manifest already had."
  ([st cfg] (sync-state! st cfg #{} nil))
  ([st cfg failed] (sync-state! st cfg failed nil))
  ([st cfg failed done]
   (let [owned (remove (fn [[t k id]] (contains? failed [t k id])) (inventory cfg))
         want (set (map (fn [[t k id]] (state/key-for t k id)) owned))
         stale (remove want (set (keys (:managed st))))
         wrote? (fn [t k id] (or (nil? done) (contains? done [t k id])))
         data-for (fn [t k id data]
                    (cond
                      (empty? data) data
                      (wrote? t k id) data
                      :else (dissoc (state/entry st t k id) :at)))]
     (as-> st $
       (reduce (fn [s [t k id data]] (state/record s t k id (or (data-for t k id data) {})))
               $ owned)
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
