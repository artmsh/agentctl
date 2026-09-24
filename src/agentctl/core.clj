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
            [agentctl.scope :as scope]
            [agentctl.ownership :as ownership]
            [agentctl.plan :as plan]
            [agentctl.skills-cli :as skills-cli]
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
    (let [projects (if (:entity-dsl? cfg)
                     (set (mapcat (fn [p]
                                    (if (contains? (:projects cfg) p) [p]
                                        (let [s (scope/selector (u/kw->str p))]
                                          (for [[id target] (:projects cfg) :when (scope/matches? s (:path target))] id)))) projects))
                     projects)
          packs (sources/packs-for cfg projects)]
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

(defn- project-hook-ops [cfg st]
  (let [desired (into {} (for [[pid p] (:projects cfg) [id h] (get-in p [:tools :claude :hooks])]
                           [(keyword (name pid) (name id)) {:project pid :project-path (:path p) :decl h}]))]
    (concat
     (keep (fn [[id {:keys [project project-path decl]}]]
             (when-let [o (plan/json-array-merge-op
                          {:tool :claude :kind :hooks :id id :project project
                           :file (str project-path "/.claude/settings.json")
                           :path [:hooks (keyword (u/kw->str (:event decl)))]
                           :old (:value (state/entry st :claude :hooks id)) :value (claude/hook-group decl)})]
               (assoc o :project-path project-path))) desired)
     (keep (fn [[[t k sid] data]]
             (let [id (keyword sid)]
               (when (and (= t :claude) (= k :hooks) (:project-path data) (not (contains? desired id)))
                 (when-let [o (plan/json-array-unset-op
                              {:tool t :kind k :id id :project (:project-id data)
                               :file (str (:project-path data) "/.claude/settings.json")
                               :path (:path data) :old (:value data)})]
                   (assoc o :project-path (:project-path data)))))) (:managed st)))))

(defn- memory-winner [cfg p t]
  (->> (:entities cfg)
       (filter (fn [e]
                 (let [ts (get-in e [:decl :tools])]
                   (and (= :memory (:kind e)) (some #{p} (:targets e))
                        (or (nil? ts) (= :all ts) (= t ts) (and (coll? ts) (some #{t} ts)))))))
       (sort-by #(vector (scope/rank (:in %) p) (if (get-in % [:decl :tools]) 1 0) (:order %)))
       last :id))

(defn- entity-extra-ops [cfg]
  (when (:entity-dsl? cfg)
    (mapcat
     (fn [{:keys [kind id in targets decl]}]
       (let [ts (or (:tools decl) (config/tools-for (if (= kind :trust) :projects kind)))
             ts (if (= :all ts) config/all-tools (if (keyword? ts) [ts] ts))]
         (for [p targets t ts
               :when (or (not= kind :memory) (= id (memory-winner cfg p t)))
               :when (or (= kind :memory)
                         (= kind :providers)
                         (and (#{:skills :skill-packs} kind) (not (common/project-skill-tools t)))
                         (and (= kind :settings) (not= t :claude)))
               :let [pid (scope/target-id p)
                     oid (keyword (name pid) (name id))
                     filename (get {:claude "CLAUDE.md" :codex "AGENTS.md" :pi "AGENTS.md"
                                    :omp "AGENTS.md" :antigravity "GEMINI.md"} t)
                     o (if (and (= kind :memory) filename)
                         (plan/link-op {:tool t :kind kind :id oid :project pid
                                        :src (u/abs-path (str/replace (u/expand (or (:from decl) (:path decl))) #"^file://" ""))
                                        :dest (str p "/" filename) :mode (:mode decl)})
                         (plan/op {:tool t :kind kind :id oid :project pid :warn true
                                   ;; a skill line already names its agents
                                   :summary (if (#{:skills :skill-packs} kind)
                                              "no project-scoped skills — not installed"
                                              (str (name t) " does not support project-scoped " (name kind)))}))]
               :when o]
           (assoc o :project-path p)))) (:entities cfg))))

(defn- tool-cfg
  "`cfg` as tool `t` sees it: each project's skills narrowed to those placed
   for `t`, and its per-tool trust."
  [cfg t]
  (update cfg :projects
          #(into {} (map (fn [[id p]]
                           [id (cond-> p
                                 (:entity-dsl? cfg)
                                 (update :skills (fn [ids]
                                                   (filterv (fn [sid]
                                                              (contains? (:tools (or (get-in cfg [:skills sid])
                                                                                    (get-in cfg [:skill-packs sid]))) t)) ids)))
                                 (:trust-by-tool p)
                                 (assoc :trusted (get (:trust-by-tool p) t)))])) %)))

(def project-skill-dirs
  {:claude claude/project-skills-dir :codex codex/project-skills-dir :antigravity antigravity/project-skills-dir})

(defn- share-project-skill-links
  "Codex and antigravity read one `<repo>/.agents/skills`. A link either tool
   wants is planned once, and never removed on behalf of the other."
  [cfg ops]
  (let [wanted (set (for [[t dir-of] project-skill-dirs
                          [_ proj] (:projects (tool-cfg cfg t))
                          :when (contains? (:for-tools proj) t)
                          [sid s] (:skills (sources/project-skills cfg proj))
                          :when (:source s)]
                      (str (dir-of proj) "/" (name sid))))
        link? #(and (= :skills (:kind %)) (#{"symlink" "copy" "unlink" "remove"} (:fs-op %)))
        unlink? #(and (= :skills (:kind %)) (= :delete (:action %)) (= :fs (:category %)))]
    (->> ops
         (remove #(and (unlink? %) (wanted (:target %))))
         (reduce (fn [[seen out] o]
                   (if (and (link? o) (seen (:target o)))
                     [seen out]
                     [(cond-> seen (link? o) (conj (:target o))) (conj out o)]))
                 [#{} []])
         second)))

(defn- skill-display
  "What the plan says about a skill or pack op: which kind of thing, which
   entity (ops for one entity across tools merge into one line), its source,
   and for a pack the skills it brings. The renderer sees ops, not cfg."
  [cfg o]
  (let [sid (keyword (name (:id o)))
        pack-line (fn [pid names]
                    (let [p (get-in cfg [:skill-packs pid])]
                      {:kind "skill-pack" :entity pid :source (sources/source-label p)
                       :skills (vec (sort names)) :alias (:alias p)}))]
    (case (:kind o)
      :skill-packs
      (let [p (get-in cfg [:skill-packs (:id o)])]
        (if (:implicit p)
          (let [skills (filter #(= (:id o) (:from (val %))) (:skills cfg))]
            {:kind "skill" :entity (:id o) :source (sources/source-label p)
             :agents (sort (distinct (mapcat (comp :tools val) skills)))
             ;; the clone is machine-wide, but the skill it fetches is placed
             ;; in projects: show it where it lands
             :in-projects (when-not (some #(= :global (:scope (val %))) skills)
                            (vec (sort (distinct (for [[_ p] (:projects cfg) sid (:skills p)
                                                       :when (contains? (into {} skills) sid)]
                                                   (:path p))))))})
          (let [users (filter #(some #{(:id o)} (:skills (val %))) (:projects cfg))
                ps (vec (sort (distinct (map (comp :path val) users))))
                global? (some #(and (= :skill-packs (:kind %)) (= (:id o) (:id %)) (= :all (:in %)))
                              (:entities cfg))]
            (assoc (pack-line (:id o) (map #(fs/file-name %) (sources/skill-dirs p)))
                   :agents (sort (if (or global? (empty? ps))
                                   (:tools p)
                                   (filter #(and (common/project-skill-tools %)
                                                 (or (nil? (:tools p)) (contains? (:tools p) %)))
                                           (distinct (mapcat (comp :for-tools val) users)))))
                   :in-projects (when-not global? (not-empty ps))))))
      :skills
      (let [s (get-in cfg [:skills sid])
            from (or (:from s)
                     (when-let [proj (get-in cfg [:projects (:project o)])]
                       (some #(when (and (get-in cfg [:skill-packs %])
                                         (some (fn [d] (= (name sid) (fs/file-name d)))
                                               (sources/skill-dirs (get-in cfg [:skill-packs %]))))
                                %)
                             (:skills proj))))
            pack (get-in cfg [:skill-packs from])]
        (cond
          (and (nil? s) (get-in cfg [:skill-packs sid])) (pack-line sid [])
          (and pack (or (nil? s) (:via-pack s))) (pack-line from [(name sid)])
          (and pack (:implicit pack)) {:kind "skill" :entity sid :source (sources/source-label pack)}
          s {:kind "skill" :entity sid :source (some-> (sources/skill-source cfg s) u/tilde)}
          :else {:kind "skill" :entity sid :source (some-> (:target o) u/tilde)}))
      nil)))

(defn- annotate-skill-ops [cfg ops]
  (map (fn [o]
         (if-let [d (and (#{:skills :skill-packs} (:kind o)) (not (:display o)) (skill-display cfg o))]
           (cond-> (assoc o :display (cond-> (update d :agents #(some->> % (map config/agent-name)))
                                       (not= :agentctl (:tool o)) (assoc :agent (config/agent-name (:tool o)))))
             (and (:project o) (not (:project-path o)))
             (assoc :project-path (get-in cfg [:projects (:project o) :path])))
           o))
       ops))

(defn- same-command
  "Two ops that run one command in one directory are one op; the first stays."
  [ops]
  (first (reduce (fn [[out seen] o]
                   (let [k (when (and (:cmds o) (:project-path o))
                             [(:project-path o) (:cmds o)])]
                     (if (and k (seen k)) [out seen] [(conj out o) (cond-> seen k (conj k))])))
                 [[] #{}] ops)))

(defn- defer-unmet-removals
  "A project copy of a user-wide skill is removed only once the user-wide
   one is installed. When it is not on disk and this plan does not install
   it — a project-scoped run leaves user-wide skills out — the removal
   would fail at execution; it is shown as kept instead."
  [ops]
  (let [installing (set (keep #(when (#{:create :update} (:action %)) (:target %)) ops))
        unmet (fn [o] (seq (remove #(or (installing %) (u/exists? (str % "/SKILL.md"))) (:requires o))))]
    (map (fn [o]
           (if-let [[g] (unmet o)]
             (-> o
                 (assoc :action :noop :warn true
                        :summary (str "kept until the user-wide " (fs/file-name g)
                                      " is installed — apply --all installs it"))
                 (dissoc :exec! :cmds :requires :owns))
             o))
         ops)))

(defn build-plan
  "All ops for the current config. Pure: touches no state on disk."
  [cfg st opts]
  (let [selected (set (selected-tools cfg (if (seq (:tools opts)) opts
                                            (assoc opts :tools (into (config/active-tools cfg)
                                                                    (concat (map first (keys (:managed st)))
                                                                            (map (comp :tool val) (:writes st))))))))
        ;; Retain vanished targets for adapter prune loops, without declaring
        ;; any desired resources there.
        cfg (reduce (fn [c [[t _ id] data]]
                      (if-let [p (:project-path data)]
                        (let [pid (or (:project-id data) (some-> id keyword namespace keyword))]
                          (if (and pid (not (get-in cfg [:projects pid])))
                            (update-in c [:projects pid]
                                       #(merge {:id pid :path p :tools {}} %
                                               {:for-tools (conj (set (:for-tools %)) t)})) c)) c))
                    cfg (:managed st))
        tool-ops (mapcat (fn [t]
                           (let [tcfg (tool-cfg cfg t)]
                             ((get-in registry [t :plan])
                              (if (and (:entity-dsl? cfg) (not (common/project-skill-tools t)))
                                (update tcfg :skills #(into {} (filter (fn [[_ s]] (= :global (:scope s)))) %)) tcfg) st))) selected)
        dups (skills-cli/global-duplicate-ops cfg tool-cfg selected
                                              {:claude claude/skills-dir :codex codex/skills-dir
                                               :antigravity antigravity/skills-dir})
        tool-ops (if (:entity-dsl? cfg)
                   (map (fn [o]
                          (cond-> o
                            (:project o) (assoc :project-path (get-in cfg [:projects (:project o) :path]))
                            (= :projects (:kind o))
                            (assoc :kind (cond
                                           (str/includes? (name (:id o)) "permissions.") :permissions
                                           (or (= "trust" (name (:id o)))
                                               (= (:id o) (:project o))) :trust
                                           :else :settings)))) tool-ops) tool-ops)]
    (-> (concat (sources/pack-ops cfg)
                (skills-cli/plan-ops cfg st tool-cfg selected)
                (share-project-skill-links cfg (remove #(and (= :skills (:kind %)) ((:drop dups) (:target %)))
                                                       tool-ops))
                ;; after the user-wide installs they check for
                (:ops dups)
                (filter #(selected (:tool %)) (ownership/prune-ops cfg st))
                (filter #(selected (:tool %)) (entity-extra-ops cfg))
                (when (and (:entity-dsl? cfg) (selected :claude)) (project-hook-ops cfg st)))
        (keep-kinds opts)
        (keep-projects cfg opts)
        defer-unmet-removals
        (->> (annotate-skill-ops cfg))
        same-command
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
   (for [[pid p] (:projects cfg) [id h] (get-in p [:tools :claude :hooks])]
     [:claude :hooks (keyword (name pid) (name id))
      {:entity-id id :project-id pid :project-path (:path p)
       :path [:hooks (keyword (u/kw->str (:event h)))] :value (claude/hook-group h)}])
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
     [t :mcps (keyword (name pid) (name mid)) {:entity-id mid :project-id pid :project-path (:path proj) :scope (:scope m)}])
   ;; a project's skill is owned user-wide only by tools that cannot host one
   ;; inside a project — for them the user's home is the only place it fits
   (for [[id s] (sources/all-skills cfg) t (:tools s)
         :when (or (= :global (:scope s))
                   (and (not (:entity-dsl? cfg)) (not (contains? common/project-skill-tools t))))]
     [t :skills id])
   ;; a project's skills are owned under the project, so the user-wide skill of
   ;; the same name is never pruned on their behalf
   (skills-cli/inventory cfg tool-cfg)
   (for [[id p] (:providers cfg) t (:tools p)] [t :providers id])
   (for [[id m] (:memory cfg) t (:tools m)] [t :memory id])
   (for [[id p] (:projects cfg) t (:for-tools p)]
     [t :projects id {:entity-id id :project-id id :project-path (:path p)}]))))

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
   (let [cfg (if (:entity-dsl? cfg)
               (assoc cfg :skills (:skills (config/normalize (:entity-raw cfg) (:source cfg)))) cfg)
         owned (remove (fn [[t k id]] (contains? failed [t k id])) (inventory cfg))
         want (set (map (fn [[t k id]] (state/key-for t k id)) owned))
         failed-keys (set (map (fn [[t k id]] (state/key-for t k id)) failed))
         done-keys (set (map (fn [[t k id]] (state/key-for t k id)) done))
         stale (filter #(and (not (want %)) (not (failed-keys %))
                             (or (nil? done) (done-keys %))) (keys (:managed st)))
         wrote? (fn [t k id] (or (nil? done) (contains? done [t k id])))
         data-for (fn [t k id data]
                    (cond
                      (empty? data) data
                      (wrote? t k id) data
                      :else (dissoc (state/entry st t k id) :at)))]
     (as-> st $
       (reduce (fn [s [t k id data]]
                 (if (or (wrote? t k id) (state/managed? s t k id))
                   (state/record s t k id (or (data-for t k id data) {})) s))
               $ owned)
       (reduce (fn [s key] (update s :managed dissoc key)) $ stale)
       (ownership/record $ cfg failed done)))))

;; ---------------------------------------------------------------- apply

(defn- fetched-sources?
  "Did this pass put new source directories on disk? A pack's skills cannot be
   enumerated before the pack exists."
  [done]
  (boolean (some #(= :skill-packs (:kind %)) done)))

(defn op-keys
  "The `(tool kind id)` resources an op stands for — several when one command
   installs a skill for several agents."
  [o]
  (or (:owns o) [[(:tool o) (:kind o) (:id o)]]))

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
      (let [cfg (if (:entity-dsl? cfg)
                  (config/normalize (:entity-raw cfg) (:source cfg)) cfg)
            {d2 :done f2 :failed} (execute! (build-plan cfg st opts))]
        {:done (concat done d2) :failed (concat failed f2)}))))
