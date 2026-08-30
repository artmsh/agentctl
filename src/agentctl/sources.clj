(ns agentctl.sources
  "Skill packs and skill source resolution.

   A pack is a checkout (git URL or a local directory). A skill is one
   directory inside a pack containing SKILL.md. Packs are materialized once
   under ~/.agents/skill-packs and every tool links to that single copy."
  (:require [agentctl.plan :as plan]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def packs-root (str u/home "/.agents/skill-packs"))

(defn pack-root [pack]
  (case (:type pack)
    :file (:root pack)
    :git (:root pack)
    nil))

(defn pack-skills-dir [pack]
  (let [root (pack-root pack)
        sub (:dir pack)]
    (cond
      (nil? root) nil
      (and sub (u/exists? (str root "/" sub))) (str root "/" sub)
      :else root)))

(defn skill-dirs
  "Directories directly under a pack that look like skills."
  [pack]
  (let [dir (pack-skills-dir pack)]
    (when (and dir (u/exists? dir))
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (filter #(u/exists? (str % "/SKILL.md")))
           (map str)
           (sort)))))

(defn skill-source
  "Absolute path of the directory backing a declared skill, or nil."
  [cfg skill]
  (or (:path skill)
      (when-let [pack (get-in cfg [:skill-packs (:from skill)])]
        (let [dir (pack-skills-dir pack)
              direct (str dir "/" (name (:id skill)))]
          (when dir
            (if (u/exists? direct)
              direct
              ;; pack may expose the skill at its root
              (when (u/exists? (str dir "/SKILL.md")) dir)))))))

(defn pack-skill
  "One skill directory inside a pack, in the same shape a declared skill has."
  [pack-id dir]
  {:id (keyword (fs/file-name dir))
   :kind :skills
   :from pack-id
   :path dir
   :source dir
   :mode :symlink})

(defn materialized? [pack]
  (boolean (some-> (pack-skills-dir pack) u/exists?)))

(defn locate-skill
  "Which declared packs carry a skill directory literally named `id` — the
   lookup that makes a `:skills {id {:from pack}}` entry optional for the
   common case. `:found` pairs a pack id with the directory found there;
   `:not-ready` lists packs not materialized yet, which cannot be ruled out
   as the skill's home. Two packs shipping the same skill name is not
   resolved by guessing — the caller decides what to do with more than one
   `:found` entry."
  [skill-packs id]
  (reduce (fn [acc [pid pack]]
            (if (materialized? pack)
              (if-let [dir (some #(when (= (name id) (fs/file-name %)) %) (skill-dirs pack))]
                (update acc :found conj [pid dir])
                acc)
              (update acc :not-ready conj pid)))
          {:found [] :not-ready []}
          skill-packs))

(defn project-skills
  "What a project's `:skills` names, resolved.

   An id is, in order: a declared skill, a whole pack — naming a pack asks for
   every skill in it, which is the only way to follow a pack that grows — or a
   skill directory found inside exactly one declared pack, which needs no
   `:skills` entry of its own at all. A pack that is not on disk yet, or that
   might hold the bare id but hasn't been scanned, can name nothing yet, so it
   is reported as pending rather than silently contributing no skills. A bare
   id found in more than one pack is ambiguous — an explicit `:skills {id
   {:from pack}}` entry is how that gets broken."
  [cfg proj]
  (reduce (fn [acc id]
            (cond
              (get-in cfg [:skills id])
              (let [s (get-in cfg [:skills id])]
                (assoc-in acc [:skills id] (assoc s :source (skill-source cfg s))))

              (get-in cfg [:skill-packs id])
              (let [pack (get-in cfg [:skill-packs id])]
                (if (materialized? pack)
                  (reduce (fn [a d]
                            (let [s (pack-skill id d)]
                              (assoc-in a [:skills (:id s)] s)))
                          acc (skill-dirs pack))
                  (update acc :pending conj id)))

              :else
              (let [{:keys [found not-ready]} (locate-skill (:skill-packs cfg) id)]
                (cond
                  (= 1 (count found))
                  (let [[pid dir] (first found)
                        s (pack-skill pid dir)]
                    (assoc-in acc [:skills (:id s)] s))

                  (seq found) (update acc :ambiguous conj {:id id :packs (mapv first found)})
                  (seq not-ready) (update acc :pending conj id)
                  :else (update acc :unknown conj id)))))
          {:skills {} :pending [] :unknown [] :ambiguous []}
          (sort (:skills proj))))

(defn for-tool
  "Skills targeting `tool`, with per-tool overrides applied and :source
   recomputed — a skill may legitimately live in a different directory for
   each agent."
  [cfg skills tool]
  (into {}
        (for [[id s] skills
              :when (contains? (:tools s) tool)
              :let [merged (merge s (get-in s [:per-tool tool]))
                    src (if (get-in s [:per-tool tool :path])
                          (get-in s [:per-tool tool :path])
                          (:source merged))]]
          [id (assoc merged :source src)])))

(defn all-skills
  "Every skill that should exist, keyed by id, with :source resolved."
  [cfg]
  (into {}
        (for [[id s] (:skills cfg)
              :let [src (skill-source cfg s)]]
          [id (assoc s :source src)])))

;; ---------------------------------------------------------------- pack ops

(defn- git-head [dir]
  (let [{:keys [exit out]} (u/sh "git" "-C" (u/abs-path dir) "rev-parse" "HEAD")]
    (when (zero? exit) (str/trim out))))

(defn- git-remote-head [uri ref]
  (let [{:keys [exit out]} (u/sh "git" "ls-remote" uri (or ref "HEAD"))]
    (when (and (zero? exit) (seq out))
      (first (str/split (str/trim out) #"\s+")))))

(defn- uri-parts
  "[label repo] for a git URI. `https://github.com/example/agent-skills` and
   `git@github.com:example/agent-skills.git` both read as `gh` + `example/agent-skills`
   — the host is a label, the path is the name people actually use."
  [uri]
  (let [s (-> (str uri) (str/replace #"^git\+" "") (str/replace #"\.git$" ""))
        [host path] (cond
                      (re-find #"^[a-z+]+://" s)
                      (let [m (re-find #"^[a-z+]+://(?:[^@/]+@)?([^/]+)/(.+)$" s)]
                        [(second m) (nth m 2)])

                      (re-find #"^[^/]+@[^:]+:" s)
                      (let [m (re-find #"^[^/]+@([^:]+):(.+)$" s)]
                        [(second m) (nth m 2)])

                      :else [nil s])]
    [(case host
       "github.com" "gh"
       "gitlab.com" "gl"
       "codeberg.org" "cb"
       (or host "git"))
     (or path s)]))

(defn- clone-cmd
  "How a person would clone it by hand: `gh` for a github repo when it is on
   PATH, git otherwise. Whatever this returns is what runs and what the plan
   prints — the line is a command, not a description of one."
  [uri ref root]
  (let [[label repo] (uri-parts uri)]
    (if (and (= "gh" label) (u/which "gh"))
      (into ["gh" "repo" "clone" repo root] (when ref ["--" "--branch" ref]))
      (into ["git" "clone" "--depth" "1"] (concat (when ref ["--branch" ref]) [uri root])))))

(defn pack-ops
  "Ops that fetch or update git-backed skill packs."
  [cfg]
  (for [[id pack] (:skill-packs cfg)
        :when (= :git (:type pack))
        :let [root (:root pack)
              present (u/exists? (str root "/.git"))
              local (when present (git-head root))
              remote (git-remote-head (:uri pack) (:ref pack))
              [label repo] (uri-parts (:uri pack))
              cmds (if present
                     [["git" "-C" root "fetch" "--all" "--tags" "--prune"]
                      ["git" "-C" root "reset" "--hard" (or (:ref pack) "@{upstream}")]]
                     [(clone-cmd (:uri pack) (:ref pack) root)])]
        :when (or (not present) (and remote local (not= local remote)))]
    (plan/op {:action (if present :update :create)
              :tool :agentctl :kind :skill-packs :id id
              :target root
              :summary (if present
                         (str label ": update pack `" repo "`"
                              " (" (some-> local (subs 0 7)) " -> " (some-> remote (subs 0 7)) ")")
                         (str label ": clone pack `" repo "` -> " (u/tilde root)))
              :cmds cmds
              :diffs [{:key :revision :before local :after remote}]
              :exec! (fn []
                       (fs/create-dirs packs-root)
                       (doseq [c cmds] (apply u/sh c)))})))
