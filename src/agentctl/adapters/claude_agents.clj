(ns agentctl.adapters.claude-agents
  "Claude Code subagent definitions. `:agents {[pack \"name\"] {:in …}}` links
   `<pack root>/agents/<name>.md` as `~/.claude/agents/<name>.md` (`:in :all`)
   or `<project>/.claude/agents/<name>.md`; Claude reads a symlinked
   definition as its own. A link agentctl made is owned, and removed only
   while it still points where agentctl pointed it: a file in its place is
   someone else's and stays."
  (:require [agentctl.adapters.claude :as claude]
            [agentctl.config :as config]
            [agentctl.plan :as plan]
            [agentctl.state :as state]
            [agentctl.util :as u]
            [babashka.fs :as fs]))

(def tool :claude)
(def kind :agents)

(defn agents-dir
  "Where Claude looks for subagent definitions: user-wide, or in a project."
  [project-path]
  (str (if project-path (str project-path "/.claude") claude/home) "/agents"))

(defn dest [project-path nm] (str (agents-dir project-path) "/" nm ".md"))

(defn placements
  "One link per place an agent is declared: `id` user-wide and `pid/id` in a
   project, the ids plugins use, so the two never stand for each other."
  [cfg]
  (for [[id a] (:agents cfg)
        :when (and (contains? (:tools a) tool) (:source a))
        [pid path] (concat (when (:global a) [[nil nil]]) (sort-by (comp str key) (:targets a)))]
    {:id (if pid (keyword (str (name pid) "/" (name id))) id)
     :entity id :name (:name a) :pack (:pack a) :source (:source a)
     :path (dest path (:name a)) :project-id pid :project-path path}))

(defn inventory
  "The ownership record: the link and the file it was made to point at."
  [cfg]
  (for [p (placements cfg)]
    [tool kind (:id p) (u/prune-nils (select-keys p [:path :source :project-id :project-path]))]))

(defn- display [entity source]
  {:kind "agent" :entity entity :agents [(config/agent-name tool)] :source (u/tilde source)})

(defn- base [{:keys [id path project-id project-path]} entity source]
  (u/prune-nils {:tool tool :kind kind :id id :target path
                 :project project-id :project-path project-path
                 :display (display entity source)}))

(defn- link-target
  "What the link at `path` points to, as an absolute path, or nil."
  [path]
  (when (fs/sym-link? path)
    (str (fs/normalize (fs/path (fs/parent path) (fs/read-link path))))))

(defn- link-op [p owned]
  (let [{:keys [source path pack name]} p
        b (base p (:entity p) source)
        root (str (fs/parent (fs/parent source)))]
    (cond
      (not (u/exists? root))
      (plan/op (assoc b :action :noop :warn true :pending true
                      :summary (str "linked once pack " (clojure.core/name pack) " is fetched")))

      (not (u/exists? source))
      (plan/op (assoc b :action :noop :warn true
                      :summary (str "no " name ".md in " (u/tilde (fs/parent source)) " — nothing linked")))

      :else
      (when-let [o (plan/link-op {:tool tool :kind kind :id (:id p) :src source :dest path
                                  :project (:project-id p)})]
        (let [current (link-target path)
              mine? (and owned (= path (:path owned)) current
                         (= (u/abs-path current) (u/abs-path (:source owned))))
              note (cond
                     (and (u/exists? path) (not current))
                     "replaces a file agentctl did not write (backed up first)"
                     (and current (not mine?))
                     (str "replaces a link to " (u/tilde current) " that agentctl did not make (backed up first)"))]
          (cond-> (merge o (dissoc b :target))
            note (assoc :note note :risk :medium)))))))

(defn- prune-op
  "Only the link agentctl made, still pointing at what it was made to point
   at, is removed. Anything else at that path is left where it is."
  [[_ _ id-str] {:keys [path source project-id project-path]}]
  (let [entity (keyword (last (clojure.string/split id-str #"/")))
        p {:id (keyword id-str) :path path :project-id project-id :project-path project-path}
        b (base p entity source)
        current (link-target path)]
    (cond
      (not (u/exists? path)) nil

      (and current source (= (u/abs-path current) (u/abs-path source)))
      (some-> (plan/unlink-op {:tool tool :kind kind :id (:id p) :dest path :project project-id})
              (merge (dissoc b :target))
              (assoc :summary (str "removed from agents.edn — delete the link " (u/tilde path))))

      :else
      (plan/op (assoc b :action :noop :warn true
                      :summary (str (u/tilde path) " is not the link agentctl made — left in place"))))))

(defn plan-ops
  "Link every declared placement; unlink each owned one no longer declared."
  [cfg st]
  (let [ps (placements cfg)
        wanted (set (map #(state/key-for tool kind (:id %)) ps))]
    (concat
     (keep #(link-op % (state/entry st tool kind (:id %))) ps)
     (keep (fn [[k data]]
             (when (and (= [tool kind] (subvec (vec k) 0 2)) (not (wanted k)))
               (prune-op k data)))
           (:managed st)))))
