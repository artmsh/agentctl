(ns agentctl.skills-cli
  "Project skills are installed by the `skills` CLI (`npx -y skills add`), run
   inside the repository. It copies each skill into `<repo>/.agents/skills` —
   the directory codex and antigravity read — links claude's
   `.claude/skills/<name>` to that copy, and records the install in
   `<repo>/skills-lock.json`. Installed for claude alone, the copy goes
   straight into `.claude/skills/<name>` and `.agents/skills` is not touched.

   The CLI has no dry run, so drift is read from disk: the lock entry, the
   copy (compared with the source for a local one), and claude's link. User-wide
   skills stay agentctl's own symlinks."
  (:require [agentctl.plan :as plan]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def agents
  "Tools with a project skills directory, as the CLI names them."
  {:claude "claude-code" :codex "codex" :antigravity "antigravity"})

(defn command
  "The CLI invocation. `AGENTCTL_SKILLS_CMD` replaces it (tests run offline)."
  []
  (or (some-> (System/getenv "AGENTCTL_SKILLS_CMD") not-empty u/split-cmd)
      ["npx" "-y" "skills"]))

(defn- canonical [dir n] (str dir "/.agents/skills/" n))
(defn- claude-link [dir n] (str dir "/.claude/skills/" n))

(defn- shared? [tools] (some #{:codex :antigravity} tools))

(defn- copy-path
  "Where the CLI keeps a name's copy for these tools: the shared directory
   when an agent that reads it is among them, claude's own otherwise."
  [dir n tools]
  (if (shared? tools) (canonical dir n) (claude-link dir n)))

(defn skill-name
  "The name the CLI installs a skill under: SKILL.md's `name:`, else the directory."
  [dir]
  (or (some->> (u/slurp-safe (str dir "/SKILL.md"))
               (re-find #"(?m)^name:\s*['\"]?([^'\"\s]+)")
               second)
      (str (fs/file-name dir))))

(defn- real [p] (or (u/real-path p) (u/abs-path p)))

(defn read-lock
  "`{name source}` from `<dir>/skills-lock.json`; a local source is stored
   relative to the repository and comes back absolute."
  [dir]
  (into {}
        (for [[n e] (:skills (u/read-json (str dir "/skills-lock.json")))
              :let [src (:source e)]]
          [(name n) (if (= "local" (:sourceType e))
                      (str (fs/normalize (fs/path (real dir) src)))
                      src)])))

;; ---------------------------------------------------------------- units

(defn- local-unit [entity dirs]
  {:entity entity :local true :dirs (vec dirs) :names (mapv skill-name dirs)})

(defn- remote-unit [entity pack select]
  {:entity entity :pack (:id pack) :source (sources/source-label pack)
   :select select :names (when-not (= ["*"] select) select)})

(defn- local-pack?
  "Installed from a directory on disk: a local pack, or a git pack agentctl
   clones itself. A git pack only projects use goes to the CLI by URL."
  [cfg p]
  (or (= :file (:type p)) (sources/cloned-for-user? cfg (:id p))))

(defn unit
  "How a project's skill id installs: a source, which skills from it, and the
   names it lands under. nil for a user-wide skill; `{:warn …}` for an id
   nothing resolves."
  [cfg id]
  (let [s (get-in cfg [:skills id])
        pack-of #(get-in cfg [:skill-packs %])
        not-fetched (fn [p] (when (and (local-pack? cfg p) (not (sources/materialized? p)))
                              {:warn (str "pack not fetched yet — installed once " (name (:id p)) " is cloned")
                               :pending true}))]
    (cond
      (and s (= :global (:scope s))) nil

      (:path s) (assoc (local-unit id [(:path s)]) :source (real (:path s)))

      s (let [p (pack-of (:from s))]
          (cond
            (nil? p) {:warn (str "skill " (name id) " names undefined pack " (:from s))}
            (not-fetched p) (not-fetched p)
            (local-pack? cfg p) (if-let [d (sources/skill-source cfg s)]
                                  (assoc (local-unit id [d]) :source (real d) :pack (:id p))
                                  {:warn (str "skill " (name id) " not found in " (name (:id p)))})
            :else (remote-unit id p (when-not (:implicit p) [(or (:subdir s) (name id))]))))

      (pack-of id)
      (let [p (pack-of id)]
        (cond
          (not-fetched p) (not-fetched p)
          (local-pack? cfg p)
          ;; a skill of the pack that is installed user-wide is not repeated here
          (when-let [dirs (seq (remove #(= :global (get-in cfg [:skills (keyword (fs/file-name %)) :scope]))
                                       (sources/skill-dirs p)))]
            (assoc (local-unit id dirs) :source (real (sources/pack-skills-dir p))
                   :pack id :select :names))
          :else (remote-unit id p ["*"])))

      :else
      (let [{:keys [found not-ready]} (sources/locate-skill (:skill-packs cfg) id)]
        (cond
          (= 1 (count found)) (let [[pid dir] (first found)]
                                (assoc (local-unit id [dir]) :source (real dir) :pack pid))
          (seq found) {:warn (str "skill " (name id) " exists in more than one pack ("
                                  (str/join ", " (map (comp name first) found))
                                  ") — declare it with its pack to disambiguate")}
          (seq not-ready) {:warn (str "pack not fetched yet — " (name id) " is resolved once "
                                      (str/join ", " (map name not-ready)) " is cloned")
                           :pending true}
          :else {:warn (str "no skill or skill-pack named " (name id) " — nothing to install")})))))

(defn- select-args [u]
  (let [sel (:select u)]
    (cond (= :names sel) (:names u)
          (seq sel) sel
          :else nil)))

(defn- installed?
  "Every name from this source is in the lock, its copy is a directory
   (matching the source when that is local), and claude — when it shares the
   copy with another agent — links to it."
  [dir u tools]
  (let [lock (read-lock dir)
        names (or (:names u) (keep (fn [[n src]] (when (= src (:source u)) n)) lock))
        by-name (zipmap (:names u) (:dirs u))]
    (and (seq names)
         (every? (fn [n]
                   (let [c (copy-path dir n tools)]
                     (and (= (:source u) (get lock n))
                          (fs/directory? c)
                          (or (not (shared? tools)) (not (fs/sym-link? c)))
                          (or (not (:local u))
                              (= (u/path-sha256 c) (u/path-sha256 (by-name n))))
                          (or (not (shared? tools)) (not (contains? tools :claude))
                              (= (u/real-path (claude-link dir n)) (u/real-path c))))))
                 names))))

(defn- run! [dir argv what]
  (let [{:keys [exit out err]}
        @(p/process argv {:dir dir :out :string :err :string
                          :extra-env {"DO_NOT_TRACK" "1" "DISABLE_TELEMETRY" "1"}})]
    (when-not (zero? exit)
      (throw (ex-info (str what " failed: " (last (remove str/blank? (str/split-lines (str err "\n" out)))))
                      {:exit exit})))))

(defn- display [cfg u tools]
  (let [p (get-in cfg [:skill-packs (:pack u)])
        whole? (and p (not (:implicit p)) (= (:entity u) (:pack u)))]
    {:kind (if whole? "skill-pack" "skill")
     :entity (:entity u)
     :source (if (:local u) (u/tilde (:source u)) (:source u))
     :skills (when whole? (:names u))
     :alias (when whole? (:alias p))
     :agents (sort (map agents tools))}))

(defn- add-op [cfg pid dir u tools]
  (when-not (installed? dir u tools)
    (let [argv (vec (concat (command) ["add" (:source u)]
                            (mapcat #(vector "-s" %) (select-args u))
                            (mapcat #(vector "-a" (agents %)) (sort tools))
                            ["-y"]))
          oid (keyword (name pid) (name (:entity u)))]
      (plan/op {:action (if (some #(fs/exists? (copy-path dir % tools)) (:names u)) :update :create)
                :tool :agentctl :kind :skills :id oid :project pid :project-path dir
                :target (str dir "/.agents/skills") :category :fs
                :owns (mapv #(vector % :skills oid) (sort tools))
                :summary (str "skills add " (:source u))
                :cmds [argv]
                :display (display cfg u tools)
                :exec! (fn [] (fs/create-dirs dir) (run! dir argv "skills add"))}))))

;; ---------------------------------------------------------------- plan

(defn wants
  "`{[pid tool] #{id}}` — each project's skill ids per tool, as `tool-cfg`
   narrows them."
  [tool-cfg cfg tools]
  (into {}
        (for [t tools
              :let [tc (tool-cfg cfg t)]
              [pid proj] (:projects tc)
              :when (contains? (:for-tools proj) t)]
          [[pid t] (set (:skills proj))])))

(defn- remove-ops
  "Skills this project no longer asks the CLI for. Removing a name takes the
   copy, every agent link and the lock entry; `-a claude-code` alone drops
   only claude's link, since the other agents read the shared copy."
  [cfg st desired tools]
  (let [gone (for [t (keys agents)
                   :when (contains? tools t)
                   id (map keyword (state/managed-ids st t :skills))
                   :when (namespace id)
                   :let [e (state/entry st t :skills id)
                         dir (:project-path e)
                         pid (keyword (namespace id))
                         entity (keyword (name id))]
                   :when (and dir (fs/directory? dir)
                              (not (contains? (get desired [pid t]) entity))
                              ;; now user-wide: `global-duplicate-ops` removes it
                              ;; once the user-wide copy is in place
                              (not= :global (get-in cfg [:skills entity :scope])))]
               {:t t :id id :pid pid :dir dir :entity entity :source (:source e)})]
    (for [[[pid dir entity] rows] (group-by (juxt :pid :dir :entity) gone)
          :let [dropped (set (map :t rows))
                staying (set (for [t (keys agents) :when (contains? (get desired [pid t]) entity)] t))
                src (some :source rows)
                lock (read-lock dir)
                names (or (not-empty (vec (sort (keep (fn [[n s]] (when (and src (= s src)) n)) lock))))
                          (when (contains? lock (name entity)) [(name entity)]))
                argv (cond
                       (empty? names) nil
                       (empty? staying) (vec (concat (command) ["remove"] names ["-y"]))
                       (and (dropped :claude) (not (staying :claude)))
                       (vec (concat (command) ["remove"] names ["-a" "claude-code" "-y"]))
                       :else nil)
                oid (keyword (name pid) (name entity))]
          o (if argv
              [(plan/op {:action :delete :tool :agentctl :kind :skills :id oid :project pid
                         :project-path dir :target (str dir "/.agents/skills") :category :fs
                         :owns (mapv #(vector % :skills oid) (sort dropped))
                         :summary (str "skills remove " (str/join " " names))
                         :cmds [argv]
                         :display {:kind "skill" :entity entity :source (or (some-> src u/tilde) (name entity))
                                   :agents (sort (map agents dropped))}
                         :exec! #(run! dir argv "skills remove")})]
              ;; installed before the CLI took over: agentctl's own links
              (when (empty? names)
                (keep identity
                      (concat
                       (when (dropped :claude)
                         [(plan/unlink-op {:tool :claude :kind :skills :project pid :id oid
                                           :dest (claude-link dir (name entity))})])
                       (when (and (some #{:codex :antigravity} dropped)
                                  (not-any? #{:codex :antigravity} staying))
                         [(plan/unlink-op {:tool (first (filter #{:codex :antigravity} (sort dropped)))
                                           :kind :skills :project pid :id oid
                                           :dest (canonical dir (name entity))})])))))
          :when o]
      o)))

(defn plan-ops
  "Install and prune ops for every project's skills, for the selected tools."
  [cfg st tool-cfg selected]
  (let [tools (set (filter selected (keys agents)))
        desired (wants tool-cfg cfg (keys agents))
        rows (for [[[pid t] ids] desired :when (tools t) id ids] [pid id t])]
    (concat
     (remove-ops cfg st desired tools)
     (for [[[pid id] rs] (sort-by (comp str key) (group-by (juxt first second) rows))
           :let [proj (get-in cfg [:projects pid])
                 dir (:path proj)
                 u (unit cfg id)
                 ts (set (map #(nth % 2) rs))]
           :when u
           o (if (:warn u)
               [(plan/op {:action :noop :warn true :pending (:pending u) :tool :agentctl :kind :skills
                          :project pid :project-path dir :id (keyword (name pid) (name id))
                          :summary (:warn u)
                          :display {:kind "skill" :entity id :source (name id)
                                    :agents (sort (map agents ts))}})]
               (keep identity [(add-op cfg pid dir u ts)]))]
       o))))

(defn- desired-names
  "Names the CLI keeps installed in `dir` for these units."
  [dir units]
  (let [lock (read-lock dir)]
    (set (mapcat (fn [u] (or (:names u) (keep (fn [[n s]] (when (= s (:source u)) n)) lock))) units))))

(defn global-duplicate-ops
  "A project copy the CLI installed of a skill that is now user-wide: removed
   with `skills remove`, so the lock stays true. `:drop` names the paths whose
   plain unlink this replaces. `global-dirs` is each tool's user-wide skills
   directory: the copy is only removed once the user-wide one is installed."
  [cfg tool-cfg selected global-dirs]
  (let [desired (wants tool-cfg cfg (keys agents))
        found (for [[pid proj] (:projects cfg)
                    :let [dir (:path proj)
                          lock (when dir (read-lock dir))]
                    :when (seq lock)
                    :let [keep-names (desired-names dir (keep #(let [u (unit cfg %)] (when-not (:warn u) u))
                                                              (mapcat #(get desired [pid %]) (keys agents))))]
                    [sid s] (:skills cfg)
                    :when (= :global (:scope s))
                    :let [src (sources/skill-source cfg s)
                          n (some-> src skill-name)
                          ts (filter #(and (contains? (:tools s) %) (contains? (:for-tools proj) %) (selected %))
                                     (keys agents))]
                    ;; the same skill: from the same source, or byte-identical
                    :when (and n (contains? lock n) (seq ts) (not (keep-names n))
                               (or (= (get lock n) (real src))
                                   (some #(= (u/path-sha256 %) (u/path-sha256 src))
                                     [(canonical dir n) (claude-link dir n)])))]
                {:pid pid :dir dir :sid sid :n n :src src :ts ts})]
    {:drop (set (mapcat (fn [{:keys [dir n]}] [(claude-link dir n) (canonical dir n)]) found))
     :ops (for [{:keys [pid dir sid n src ts]} found
                :let [argv (vec (concat (command) ["remove" n "-y"]))]]
            (plan/op {:action :delete :tool :agentctl :kind :skills :project pid :project-path dir
                      :id (keyword (name pid) (name sid)) :target (canonical dir n) :category :fs
                      :owns [] :summary (str "skills remove " n " — installed user-wide")
                      :requires (mapv #(str (global-dirs %) "/" (name sid)) ts)
                      :cmds [argv]
                      :display {:kind "skill" :entity sid :source (u/tilde src) :agents (sort (map agents ts))}
                      :exec! (fn []
                               (doseq [t ts :let [g (str (global-dirs t) "/" (name sid))]]
                                 (when-not (u/exists? (str g "/SKILL.md"))
                                   (throw (ex-info "global skill is not installed; keeping project copy"
                                                   {:skill sid :global g}))))
                               (run! dir argv "skills remove"))}))}))

(defn inventory
  "Ownership of project skills: one `[tool :skills project/id]` per agent,
   carrying the source so a later prune can name what the CLI installed."
  [cfg tool-cfg]
  (for [[[pid t] ids] (wants tool-cfg cfg (keys agents))
        id ids
        :let [u (unit cfg id)]
        :when (and u (not (:warn u)))
        :let [proj (get-in cfg [:projects pid])]]
    [t :skills (keyword (name pid) (name id))
     {:entity-id id :project-id pid :project-path (:path proj) :source (:source u)}]))
