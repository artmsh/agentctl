(ns agentctl.core
  "Planning engine: turn a normalized config into ops, execute them, track state."
  (:require [agentctl.adapters.antigravity :as antigravity]
            [agentctl.adapters.claude :as claude]
            [agentctl.adapters.claude-agents :as claude-agents]
            [agentctl.adapters.codex :as codex]
            [agentctl.adapters.common :as common]
            [agentctl.adapters.dsh :as dsh]
            [agentctl.adapters.kimi :as kimi]
            [agentctl.adapters.llm :as llm]
            [agentctl.adapters.omp :as omp]
            [agentctl.adapters.pi :as pi]
            [agentctl.config :as config]
            [agentctl.extensions :as extensions]
            [agentctl.scope :as scope]
            [agentctl.ownership :as ownership]
            [agentctl.plan :as plan]
            [agentctl.skills-cli :as skills-cli]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.statusline :as statusline]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def registry
  {:claude {:plan claude/plan :present? claude/present?}
   :codex  {:plan codex/plan  :present? codex/present?}
   :pi     {:plan pi/plan     :present? pi/present?}
   :omp    {:plan omp/plan    :present? omp/present?}
   :llm    {:plan llm/plan    :present? llm/present?}
   :kimi   {:plan kimi/plan   :present? kimi/present?}
   :dsh    {:plan dsh/plan    :present? dsh/present?}
   :antigravity {:plan antigravity/plan :present? antigravity/present?}})

(defn- selected-tools
  "`--tool` is a direct order and outranks the config; without one the config
   decides, through the executors its projects name."
  [cfg {:keys [tools]}]
  (let [wanted (or (not-empty (set tools)) (config/active-tools cfg))]
    (filter #(and (contains? wanted %) ((get-in registry [% :present?]))) config/all-tools)))

(def kinds
  "Every kind an op can carry: the DSL's sections and every kind a tool is
   provisioned with, plus the shared status-line file, which belongs to no
   tool. An old-format config plans a project's trust and settings as
   `:projects`, and extensions have no DSL section. `-k` and
   `--only` are checked against this, because a kind no op carries selects
   nothing, and an empty plan reads exactly like a converged machine — and,
   once a config is loaded, against `kinds-for` its format."
  (vec (sort (conj (into (set scope/kinds) (mapcat val config/capabilities)) statusline/kind))))

(def dsl-refiled
  "The kinds the two config formats name differently. Adapters plan a
   project's trust, permissions and settings as `:projects` ops in either
   format, the old format's project registry being where all three are
   declared. The entity DSL has no `:projects` section, and `build-plan`
   refiles each such op under the section that declared it (`dsl-section`).
   So `:projects` carries nothing in the DSL, and a target no adapter plans
   under its own name — `:trust` — carries nothing in the old format."
  {:projects #{:trust :permissions :settings}})

(defn- dsl-section
  "The DSL section a `:projects` op is refiled under — one of `dsl-refiled`'s."
  [o]
  (cond
    (str/includes? (name (:id o)) "permissions.") :permissions
    (or (= "trust" (name (:id o))) (= (:id o) (:project o))) :trust
    :else :settings))

(defn kinds-for
  "The kinds an op can carry under `cfg`'s format: `kinds` less those only the
   other format plans (see `dsl-refiled`)."
  [cfg]
  (let [adapter-kinds (set (mapcat val config/capabilities))
        dead (if (:entity-dsl? cfg)
               (set (keys dsl-refiled))
               (set (remove adapter-kinds (mapcat val dsl-refiled))))]
    (vec (remove dead kinds))))

(defn- format-name [cfg] (if (:entity-dsl? cfg) "an entity-DSL config" "an old-format config"))

(defn kind-problem
  "Why `k` is no kind to select — with `cfg`, also a kind `cfg`'s format
   plans nothing as. A config whose format nothing decides yet (`{}`, or only
   format-neutral keys; see `config/config-format`) is planned the old way,
   so the same kinds select nothing in it, but the reason names no format."
  ([k]
   (str "unknown kind " k " (kinds: " (str/join ", " (map name kinds)) ")"))
  ([cfg k]
   (when (and (some #{k} kinds) (not (some #{k} (kinds-for cfg))))
     (if-not (:format cfg)
       (str "selects nothing in this config: nothing in it decides between the entity DSL"
            " and the old format yet")
       (let [instead (map name (sort (if (:entity-dsl? cfg)
                                       (get dsl-refiled k)
                                       (for [[from to] dsl-refiled :when (contains? to k)] from))))]
         (str (format-name cfg) " plans nothing as " (name k) "; it files those ops under "
              (if (next instead) (str (str/join ", " (butlast instead)) " or " (last instead)) (first instead))
              " (kinds for this config: " (str/join ", " (map name (kinds-for cfg))) ")"))))))

(defn dead-selectors
  "`[flag value message level]` for each `-k` kind, and each `--only` kind,
   that is a kind but one `cfg`'s format plans nothing as: `-k projects` on
   an entity-DSL config, `-k trust` on an old-format one. Such a run plans
   nothing and exits 0 — exactly what a converged machine looks like — so it
   is an `:error`. Where the format is still undecided the run is only told,
   with a `:warn`: the next section added may decide it either way."
  [cfg {:keys [kinds only]}]
  (let [level (if (:format cfg) :error :warn)]
    (concat
     (for [k kinds :let [p (kind-problem cfg k)] :when p] ["-k" (name k) p level])
     (for [[k id] only :let [p (kind-problem cfg k)] :when p] ["--only" (str (name k) "/" id) p level]))))

(defn unknown-selectors
  "`[flag value message]` for each `:tools` / `:kinds` value that selects
   nothing — the CLI's `-t`/`-k` and the GUI's filters alike. A tool without
   an adapter or a kind no op carries plans nothing, and an empty plan reads
   exactly like a converged machine."
  [{:keys [tools] :as opts}]
  (concat
   (for [t tools :when (not (some #{t} config/all-tools))]
     ["-t" t (str (config/tool-problem t) " (tools: " (str/join ", " (map name config/all-tools)) ")")])
   (for [k (:kinds opts) :when (not (some #{k} kinds))]
     ["-k" k (kind-problem k)])))

(defn kind-selected?
  "Does `-k` let ops of `kind` through? Selecting skills or agents lets the
   `:skill-packs` ops through too: a pack op exists only because some skill
   or agent placement needs its clone, and without it one from a pack not on
   disk yet is never linked while the run reports nothing to change."
  [{:keys [kinds]} kind]
  (or (empty? kinds)
      (contains? (set kinds) kind)
      (and (= :skill-packs kind) (some #{:skills :agents} kinds) true)))

(defn- keep-kinds
  "`-k` keeps ops of the kinds named, and skills keep their packs. Later
   filters (`-p`, `--only`, `--user`) narrow the packs with everything else."
  [ops opts]
  (filter #(kind-selected? opts (:kind %)) ops))

(defn- id-names
  "The spellings `--only` accepts for an id: whole, and its last segment.
   A project's copy of an entity carries the project in its id
   (`target-…/review`), and the selector names the entity, not the copy."
  [id]
  (let [s (u/id->str id)] (hash-set s (last (str/split s #"/")))))

(defn- only-match? [[kind id] o]
  (and (or (= kind (:kind o)) (= kind (:shown-kind o)))
       (some? (:id o))
       (contains? (id-names (:id o)) id)))

(defn- only-skill-packs
  "The packs `--only skills/<sid>` has to fetch. A declared skill (by the
   config's `:skills`, a pack of that name, or a project's `:skills`) brings
   the packs `sources/packs-for-skill` names for it. Any other id brings at
   most the pack it already sits in on disk: the lookup for an undeclared id
   falls back to every pack not fetched yet, so a typo — or a skill dropped
   from the config, whose removal needs no clone — would fetch them all."
  [cfg sid]
  (if (or (get-in cfg [:skills sid]) (get-in cfg [:skill-packs sid])
          (some #(some #{sid} (:skills %)) (vals (:projects cfg))))
    (sources/packs-for-skill cfg sid)
    (for [[pid p] (:skill-packs cfg) d (sources/skill-dirs p)
          :when (= (name sid) (str (fs/file-name d)))]
      pid)))

(defn- keep-only
  "`--only kind/id` keeps the ops of one entity wherever it is placed, user
   scope and projects alike. A selected skill keeps the op that fetches its
   pack, for the reason `keep-projects` gives: without the clone there is
   nothing to link, and `converge!` never gets its second pass."
  [ops cfg {:keys [only]}]
  (if (empty? only)
    ops
    (let [packs (set (concat
                      (for [[kind id] only :when (= :skills kind)
                            p (only-skill-packs cfg (keyword id)) :when p] p)
                      (for [[kind id] only :when (= :agents kind)
                            [aid a] (:agents cfg) :when (= id (name aid))] (:pack a))))]
      (filter #(or (some (fn [sel] (only-match? sel %)) only)
                   (and (= :skill-packs (:kind %)) (contains? packs (:id %))))
              ops))))

(defn- keep-user
  "`--user` keeps what lands outside every project: user-wide files, and the
   packs cloned for them, which belong to no project either."
  [ops {:keys [user]}]
  (if user (remove #(or (:project %) (:project-path %)) ops) ops))

(defn- project-ids
  "The declared projects one `--project` value selects: a project id as is,
   otherwise a HOME-relative path or tree."
  [cfg p]
  (cond
    (contains? (:projects cfg) p) [p]
    (:entity-dsl? cfg)
    ;; id->str, not kw->str: `-p projects/x` arrives as a keyword, which files
    ;; everything before the first slash under its namespace
    (let [s (scope/selector (u/id->str p))]
      (for [[id target] (:projects cfg) :when (scope/matches? s (:path target))] id))
    :else [p]))

(defn unmatched-projects
  "`--project` values that select nothing declared. A run narrowed to nothing
   plans nothing, which reads exactly like a converged machine."
  [cfg {:keys [projects]}]
  (sort (for [p projects :when (not-any? #(contains? (:projects cfg) %) (project-ids cfg p))]
          (u/id->str p))))

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
    (let [projects (set (mapcat #(project-ids cfg %) projects))
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
  "Did `-t`/`-k`/`-p`/`--only`/`--user` narrow this run to less than the
   whole config? Callers use this to decide what `done` to pass
   `sync-state!` — see its docstring. A scoped run forgets only what it
   removed itself, so nothing outside the selection is ever pruned."
  [{:keys [tools kinds projects only user]}]
  (boolean (or (seq tools) (seq kinds) (seq projects) (seq only) user)))

(defn- project-hook-ops [cfg st]
  (let [desired (into {} (for [[pid p] (:projects cfg) [id h] (get-in p [:tools :claude :hooks])]
                           [(keyword (name pid) (name id)) {:project pid :project-path (:path p) :decl h}]))]
    (concat
     (keep (fn [[id {:keys [project project-path decl]}]]
             (when-let [o (plan/json-array-merge-op
                          {:tool :claude :kind :hooks :id id :project project
                           :file (str project-path "/.claude/settings.json")
                           :path [:hooks (keyword (u/kw->str (:event decl)))]
                           :old (:value (state/entry st :claude :hooks id)) :value (claude/hook-group decl)
                           ;; as user-scope hooks do: under --show-noop a
                           ;; converged hook is the confirmation a change landed
                           :report-converged? true})]
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
                                    :omp "AGENTS.md" :antigravity "GEMINI.md" :kimi "AGENTS.md"
                                    :dsh "AGENTS.md"} t)
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
  "`cfg` as tool `t` sees it: each MCP server at the scope `t` keeps it in,
   each project's skills narrowed to those placed for `t`, and its per-tool trust."
  [cfg t]
  (update (update cfg :mcps
                  #(into {} (map (fn [[id m]] [id (assoc m :scope (common/mcp-scope m t))])) %))
          :projects
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
  {:claude claude/project-skills-dir :codex codex/project-skills-dir
   :antigravity antigravity/project-skills-dir :kimi kimi/project-skills-dir
   :dsh dsh/project-skills-dir :pi pi/project-skills-dir})

(defn- share-project-skill-links
  "Codex, antigravity, kimi, pi and the DeepSeek Harness read one
   `<repo>/.agents/skills`. A link any of them wants is planned once, and
   never removed on behalf of another."
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
            ;; what this tool's link actually points at: the line must name
            ;; the source the op links, or a repointed link reads as converged
            override (get-in s [:per-tool (:tool o) :path])
            from (or (:from s)
                     (when-let [proj (get-in cfg [:projects (:project o)])]
                       (some #(when (and (get-in cfg [:skill-packs %])
                                         (some (fn [d] (= (name sid) (fs/file-name d)))
                                               (sources/skill-dirs (get-in cfg [:skill-packs %]))))
                                %)
                             (:skills proj))))
            pack (get-in cfg [:skill-packs from])]
        (cond
          override {:kind "skill" :entity sid :source (u/tilde (u/abs-path override))}
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

(defn- skill-readers
  "The agents that read project `p`'s shared `.agents/skills`: those placed
   there, pi unless it never reads the project's skills. A copy there is
   theirs as much as the agent's whose user-wide skill it repeats."
  [p]
  (let [as-pi (cond-> p (:trust-by-tool p) (assoc :trusted (get (:trust-by-tool p) :pi)))]
    (set (filter #(and (contains? sources/shared-readers %)
                       (or (not= :pi %) (not= :untrusted (pi/trust as-pi))))
                 (:for-tools p)))))

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
        cfg (update cfg :projects #(into {} (map (fn [[id p]] [id (assoc p :skill-readers (skill-readers p))])) %))
        tool-ops (mapcat (fn [t]
                           (let [tcfg (tool-cfg cfg t)]
                             (concat
                              ((get-in registry [t :plan])
                               (if (and (:entity-dsl? cfg) (not (common/project-skill-tools t)))
                                 (update tcfg :skills #(into {} (filter (fn [[_ s]] (= :global (:scope s)))) %)) tcfg) st)
                              (map plan/op (common/caps-not-written-ops tcfg t))))) selected)
        dups (skills-cli/global-duplicate-ops cfg tool-cfg selected
                                              {:claude claude/skills-dir :codex codex/skills-dir
                                               :antigravity antigravity/skills-dir
                                               :kimi kimi/skills-dir :dsh dsh/skills-dir
                                               :pi pi/skills-dir})
        tool-ops (if (:entity-dsl? cfg)
                   (map (fn [o]
                          (cond-> o
                            (:project o) (assoc :project-path (get-in cfg [:projects (:project o) :path]))
                            (= :projects (:kind o)) (assoc :kind (dsl-section o)))) tool-ops)
                   tool-ops)]
    (-> (concat (sources/pack-ops cfg)
                (extensions/source-ops cfg selected)
                (skills-cli/plan-ops cfg st tool-cfg selected)
                ;; a collision report names the copy as well, but says what pi
                ;; loads there, which holds whether the copy is kept or removed
                (share-project-skill-links cfg (remove #(and (= :skills (:kind %)) (not (:collision %))
                                                             ((:drop dups) (:target %)))
                                                       tool-ops))
                ;; after the user-wide installs they check for
                (:ops dups)
                (statusline/plan-ops cfg st opts)
                (filter #(selected (:tool %)) (ownership/prune-ops cfg st))
                (filter #(selected (:tool %)) (entity-extra-ops cfg))
                (when (and (:entity-dsl? cfg) (selected :claude)) (project-hook-ops cfg st))
                (when (selected :claude) (claude-agents/plan-ops cfg st)))
        (keep-kinds opts)
        (keep-projects cfg opts)
        (keep-only cfg opts)
        (keep-user opts)
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
  (concat
   ;; the status-line file belongs to no CLI, so no executor selects it
   (statusline/inventory cfg)
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
   ;; claude's servers carry the env and header names they declare: one
   ;; dropped from the declaration is agentctl's to remove, the rest the user's
   (for [[id m] (:mcps cfg) t (:tools m) :when (= :global (common/mcp-scope m t))]
     (if (= :claude t)
       [t :mcps id (claude/declared-names (common/for-this-tool m t))]
       [t :mcps id]))
   (for [[pid proj] (:projects cfg)
         mid (:mcp proj)
         :let [m (get-in cfg [:mcps mid])]
         :when m
         t (:tools m)
         :when (and (common/project-scopes (common/mcp-scope m t))
                    (contains? common/project-mcp-tools t))]
     [t :mcps (keyword (name pid) (name mid))
      (merge {:entity-id mid :project-id pid :project-path (:path proj) :scope (common/mcp-scope m t)}
             (when (= :claude t) (claude/declared-names m)))])
   ;; a project's skill is owned user-wide only by tools that cannot host one
   ;; inside a project — for them the user's home is the only place it fits
   (for [[id s] (sources/all-skills cfg) t (:tools s)
         :when (or (= :global (:scope s))
                   (and (not (:entity-dsl? cfg)) (not (contains? common/project-skill-tools t))))]
     [t :skills id])
   ;; a project's skills are owned under the project, so the user-wide skill of
   ;; the same name is never pruned on their behalf
   (skills-cli/inventory cfg tool-cfg)
   ;; Claude plugins are installed by scope. Keep the source and destination in
   ;; the manifest so a later removal can uninstall only what agentctl owns.
   (for [[id p] (:plugins cfg) :when (and (= :global (:scope p)) (contains? (:tools p) :claude))]
     [:claude :plugins id {:scope :global :plugin (:plugin p) :marketplace (:marketplace p)
                           :repo (:repo p) :uri (:uri p) :ref (:ref p)}])
   (for [[pid proj] (:projects cfg)
         :when (contains? (:for-tools proj) :claude)
         id (:plugins proj)
         :let [p (get-in cfg [:plugins id])]
         :when (and p (contains? (:tools p) :claude))]
     [:claude :plugins (keyword (str (name pid) "/" (name id)))
      {:scope (:scope p) :plugin (:plugin p) :marketplace (:marketplace p)
       :repo (:repo p) :uri (:uri p) :ref (:ref p) :project-id pid :project-path (:path proj)}])
   ;; a subagent link records where it went and what it points at, so a
   ;; removal can tell it from a file someone put there since
   (claude-agents/inventory cfg)
   (for [[id p] (:providers cfg) t (:tools p)] [t :providers id])
   ;; `defaultTools` is an array shared with the user; what an extension put
   ;; in it has to be remembered to be taken out again
   (for [[id e] (:extensions cfg) t (:for-tools e)]
     [t :extensions id {:root (:root e) :default-tools (vec (:default-tools e))}])
   (for [[id m] (:memory cfg) t (:tools m)] [t :memory id])
   (for [[id p] (:projects cfg) t (:for-tools p)]
     [t :projects id {:entity-id id :project-id id :project-path (:path p)}])))))

(def ^:private field-kinds
  "Kinds whose op ids name a field or a file, not a declared entity, so there
   is no declaration to check an `--only` id against."
  #{:settings :permissions :trust :projects :statusline})

(defn unmatched-only
  "`--only` selectors whose id names nothing declared or owned, as written.
   Checked against declarations, never against ops: a converged entity plans
   no op, and a run narrowed to a typo plans nothing, which reads exactly like
   a converged machine."
  [cfg st {:keys [only]}]
  (let [declared (fn [kind]
                   (concat (for [[_ k id] (inventory cfg) :when (= k kind)] id)
                           (for [[[_ k id] _] (:managed st) :when (= k kind)] id)
                           (for [e (:entities cfg) :when (= kind (:kind e))] (:id e))
                           (let [section (get cfg kind)] (when (map? section) (keys section)))
                           (when (= :skills kind)
                             (concat (mapcat :skills (vals (:projects cfg)))
                                     (for [[_ p] (:skill-packs cfg) d (sources/skill-dirs p)]
                                       (fs/file-name d))))))]
    (for [[kind id :as sel] only
          :when (not (field-kinds kind))
          :when (not-any? #(contains? (id-names %) id) (declared kind))]
      (str (name kind) "/" (second sel)))))

(declare op-keys)

(defn- mark-approvals
  "A Claude `.mcp.json` server whose name agentctl added to the project's
   `enabledMcpjsonServers` is marked `:approved`, so removing the server can
   take the approval with it; a name that was there already is the user's,
   and never marked. Recording rebuilds an entry from the declaration, so
   the mark is carried over while the server stays at `:project` scope, and
   set for the servers a completed approval op added (`:approves`)."
  [s old cfg failed done]
  (let [ok? (fn [o] (let [k (first (op-keys o))]
                      (and (not (failed k)) (or (nil? done) (done k)))))
        project? (fn [s k] (= :project (get-in s [:managed k :scope])))
        s (reduce (fn [s [k e]]
                    (cond-> s
                      (and (:approved e) (= :project (:scope e)) (project? s k))
                      (assoc-in [:managed k :approved] true)))
                  s (:managed old))]
    (reduce (fn [s o]
              (reduce (fn [s id]
                        (let [k (state/key-for :claude :mcps id)]
                          (cond-> s (project? s k) (assoc-in [:managed k :approved] true))))
                      s (when (ok? o) (:approves o))))
            s (:planned-ops cfg))))

(defn sync-state!
  "Record what we now own; forget resources dropped from the config.
   `failed` (tool kind id) triples are left unrecorded — claiming ownership of
   something we never managed to create would make a later prune delete it.

   `done` (tool kind id) triples are the ops a *scoped* run (`-t`/`-k`/`-p`/
   `--only`/`--user`, or the working directory) actually executed — pass nil, not `#{}`, for an unfiltered run. Most
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
       (mark-approvals $ st cfg failed done)
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

(defn unresolved
  "Ops held back because a secret did not resolve (`common/unresolved-op`).
   Nothing was written for them, so a run holding one has not converged:
   `apply` exits 2 and `apply!` exits 1, and like a failed op none of them is
   claimed as owned — a later removal from agents.edn would otherwise prune a
   hand-made server of the same name that agentctl never wrote."
  [ops]
  (filter :unresolved ops))

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
