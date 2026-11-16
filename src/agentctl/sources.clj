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
              direct (str dir "/" (or (:subdir skill) (name (:id skill))))]
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

(defn source-label
  "A pack's source as a person would name it: `owner/repo` for GitHub, a
   `~`-relative path for a directory."
  [pack]
  (let [uri (str (:uri pack))]
    (or (second (re-find #"^(?:https://|git@)github\.com[/:]([^/]+/[^/]+?)(?:\.git)?/?$" uri))
        (when (= :file (:type pack)) (u/tilde (:root pack)))
        uri)))

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
  (update
   (reduce (fn [acc id]
            (cond
              (get-in cfg [:skills id])
              (let [s (get-in cfg [:skills id])
                    src (skill-source cfg s)
                    pack (get-in cfg [:skill-packs (:from s)])]
                (if (and (nil? src) pack (not (materialized? pack)))
                  (update acc :pending conj (:from s))
                  (assoc-in acc [:skills id] (assoc s :source src))))

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
          (sort (:skills proj)))
   :skills #(into {} (remove (fn [[id s]]
                              (= :global (:scope (get-in cfg [:skills id] s))))) %)))

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

;; ---------------------------------------------------------------- project copies

(defn skill-name
  "The name a skill goes by: SKILL.md's `name:`, else its directory. The
   `skills` CLI installs under it, and pi detects collisions by it."
  [dir]
  (or (some->> (u/slurp-safe (str dir "/SKILL.md"))
               (re-find #"(?m)^name:\s*['\"]?([^'\"\s]+)")
               second)
      (str (fs/file-name dir))))

(def project-skill-roots
  "Where in a repository the agents look for skills. A link in one of them may
   lead into a copy kept in another, as claude's `.claude/skills/<name>` leads
   into the `skills` CLI's `.agents/skills/<name>`."
  [".claude/skills" ".agents/skills" ".pi/skills"])

(defn leaf-path
  "`p` with the directories above it resolved and its last segment kept as
   written: where a link itself lives, as opposed to where it leads."
  [p]
  (let [a (u/abs-path p)]
    (if-let [parent (fs/parent a)]
      (str (u/real-path (str parent)) "/" (fs/file-name a))
      a)))

(defn- hops
  "Every path `p` passes through on its way to what it names: the path as
   written, each link it meets, and where it lands."
  [p]
  (loop [cur (leaf-path p) out []]
    (if (and (fs/sym-link? cur) (< (count out) 40))
      (let [t (fs/read-link cur)]
        (recur (leaf-path (str (if (fs/absolute? t) t (fs/path (fs/parent cur) t))))
               (conj out cur)))
      (conj out cur))))

(defn- under? [parent p] (or (= parent p) (str/starts-with? p (str parent "/"))))

(defn placements
  "Every place a declared skill is linked from, per tool — a `:per-tool :path`
   included — and every pack's skills directory. A project copy any of them
   leads into is some agent's live source, however redundant it looks."
  [cfg]
  (concat
   (for [[sid s] (:skills cfg)
         :let [src (skill-source cfg s)]
         t (or (seq (sort (:tools s))) [nil])
         :let [p (or (get-in s [:per-tool t :path]) src)]
         :when p]
     {:path p :why (str (if t (str (name t) "'s ") "") (name sid) " is linked from it")})
   (for [[pid pack] (:skill-packs cfg)
         :let [d (pack-skills-dir pack)]
         :when d]
     {:path d :why (str "it is part of pack " (name (or (:alias pack) pid)))})))

(defn- placed-in
  "The placement that needs `p`, or nil. A directory is needed while a
   placement leads into it; a link only while a placement passes through the
   link itself — removing a link never touches what it points at. Either is
   needed while it lies inside a placement, as a skill inside a pack does."
  [cfg p]
  (let [link? (fs/sym-link? p)
        at (if link? (leaf-path p) (u/real-path p))]
    (some (fn [{:keys [path why]}]
            (let [hs (hops path)]
              (when (or (if link? (some #(= at %) hs) (some #(under? at %) hs))
                        (under? (last hs) at))
                why)))
          (placements cfg))))

(defn git-tracked?
  "Does git track anything at `p` in the repository at `root`? A committed
   copy is the project's own, whatever it matches."
  [root p]
  (when (and root (u/resolves? root))
    (let [r (u/real-path root)
          rel (str (fs/relativize r (leaf-path p)))
          {:keys [exit out]} (u/sh "git" "-C" r "ls-files" "--" rel)]
      (and (zero? exit) (not (str/blank? out))))))

(defn links-into
  "Links in the project's skill directories, under `p`'s name, that lead into
   the copy at `p` — they would dangle once it is gone. A dangling one is
   included: it has already lost its target."
  [root p]
  (let [at (u/real-path p)]
    (for [r project-skill-roots
          :let [l (str root "/" r "/" (fs/file-name p))]
          :when (and (fs/sym-link? l) (not= (leaf-path l) (leaf-path p))
                     (or (not (u/resolves? l)) (some #(under? at %) (hops l))))]
      l)))

(defn kept-because
  "Why the project copy at `p` must stay, or nil: some placement needs it, or
   git tracks it, or a link git tracks leads into it."
  [cfg root p]
  (or (placed-in cfg p)
      (when (git-tracked? root p) "it is tracked by git")
      (when (some #(git-tracked? root %) (when-not (fs/sym-link? p) (links-into root p)))
        "a link tracked by git leads into it")))

(def shared-readers
  "Agents that read a repository's shared `.agents/skills`."
  #{:codex :antigravity :kimi :dsh :pi})

(defn- readers
  "The agents that read skills from the project directory `dir` is: the
   shared `.agents/skills` serves every agent placed in the project
   (`:skill-readers`, which leaves out pi where it reads no project skills),
   `.claude/skills` serves claude and `.pi/skills` pi."
  [proj dir]
  (let [root (:path proj)
        shared (set (or (:skill-readers proj) (filter shared-readers (:for-tools proj))))
        at #(= (leaf-path dir) (leaf-path (str root "/" %)))]
    (cond (at ".agents/skills") shared
          (at ".claude/skills") #{:claude}
          (at ".pi/skills") (set (filter #{:pi} shared))
          :else #{})))

(defn needed-by
  "Why the copy at `p` in project `proj` must stay for agents that read it
   and have no user-wide `s` (named `n`), or nil: removing it would take the
   skill from them. A link in the shared `.agents/skills` or in `.pi/skills`
   is how its readers get the skill, as much as a copy would be; claude's own
   `.claude/skills` link is only its way into a copy, and claude, where it is
   placed, reads one its link leads into, which goes with the copy."
  [proj p s n]
  (let [root (:path proj)
        dir (str (fs/parent (u/abs-path p)))
        claude-link (leaf-path (str root "/.claude/skills/" (fs/file-name p)))
        readers (if (fs/sym-link? p)
                  (when (and (u/resolves? p)
                             (not= (leaf-path dir) (leaf-path (str root "/.claude/skills"))))
                    (readers proj dir))
                  (cond-> (readers proj dir)
                    (and (contains? (set (:for-tools proj)) :claude)
                         (some #(= claude-link (leaf-path %)) (links-into root p)))
                    (conj :claude)))
        missing (sort (remove #(contains? (set (:tools s)) %) readers))]
    (when (seq missing)
      (str (str/join ", " (map name missing)) (if (next missing) " read" " reads")
           " it here and " (if (next missing) "have" "has") " no user-wide " n))))

(defn lock-names
  "Names the `skills` CLI recorded as installed in the repository at `root`."
  [root]
  (set (map u/key-str (keys (:skills (u/read-json (str root "/skills-lock.json")))))))

(defn kept-op
  "The report for a project copy that stays. `:conflict` marks it as one
   `validate` repeats: a converged plan prints no warnings."
  [m dest why]
  (plan/op (merge m {:action :noop :warn true :conflict true :target dest
                     :summary (str "project copy " (u/tilde dest) " kept — " why)})))

(defn global-project-unlink-ops
  "Remove redundant project copies of declared global skills — a symlink or a
   local directory, including ones predating the ownership manifest — only
   when it is byte-identical to the skill's source. One that differs is
   another skill of the same name: reported, kept. So is one some placement
   still needs (`kept-because`), and one an agent reads that has no
   user-wide copy of its own (`needed-by`). A directory goes with the links into it,
   after a backup. A copy the `skills` CLI installed is left to
   `skills-cli/global-duplicate-ops`, which takes its lock entry with it.
   Check the global install again at execution time before removing."
  [cfg tool pid project-dir global-dir]
  (for [[sid s] (for-tool cfg (all-skills cfg) tool)
        :when (= :global (:scope s))
        :let [dest (str project-dir "/" (name sid))
              global (str global-dir "/" (name sid))
              link? (fs/sym-link? dest)
              dangling? #(and link? (not (u/resolves? dest)))
              same? #(and (:source s) (u/same-content? (u/real-path dest) (:source s)))]
        :when (or link? (fs/directory? dest))
        :let [root (get-in cfg [:projects pid :path])
              id (keyword (name pid) (name sid))
              base {:tool tool :kind :skills :project pid :id id}
              kept (or (kept-because cfg root dest)
                       (when-not (dangling?)
                         (needed-by (get-in cfg [:projects pid]) dest s (name sid))))
              links (when-not link? (links-into root dest))
              o (cond
                  kept (kept-op base dest kept)
                  ;; a dangling link points nowhere; nothing is lost removing it
                  (dangling?) (plan/unlink-op (assoc base :dest dest))
                  (not (same?))
                  (plan/op (merge base {:action :noop :warn true :conflict true :target dest
                                        :summary (str "local copy differs from the user-wide " (name sid) " — kept")}))
                  (and (not link?) (contains? (lock-names root) (fs/file-name dest))) nil
                  :else (plan/unlink-op (assoc base :dest dest)))]
        :when o]
    (if (= :noop (:action o))
      o
      (assoc o :fs-op (if link? "unlink" "remove")
               :requires [global]
               :entry "folder"
               :summary (if link?
                          (str "unlink " (u/tilde dest))
                          (str "remove " (u/tilde dest) " — identical to the user-wide skill"))
               :cmds (into [(if link? ["unlink" dest] ["rm" "-r" dest])]
                           (for [l links] ["unlink" l]))
               :exec! (fn []
                        (when-not (u/exists? (str global "/SKILL.md"))
                          (throw (ex-info "global skill is not installed; keeping project copy"
                                          {:skill sid :global global})))
                        (when-not (or (dangling?) (same?))
                          (throw (ex-info "project skill changed since planning" {:path dest})))
                        (doseq [p (cons dest links) :when (u/exists? p)]
                          (u/backup! p)
                          (fs/delete-tree p {:nofollow-links true})))))))

(defn packs-for-skill
  "Pack ids skill `id` could come out of: its declared `:from`, the pack of
   that name, or, for a bare id, every pack carrying it plus every pack not
   on disk yet."
  [cfg id]
  (cond
    (get-in cfg [:skills id]) [(:from (get-in cfg [:skills id]))]
    (get-in cfg [:skill-packs id]) [id]
    :else (let [{:keys [found not-ready]} (locate-skill (:skill-packs cfg) id)]
            (concat (map first found) not-ready))))

(defn packs-for
  "Pack ids the given projects could need. A project names a skill by one of
   three routes — a declared skill (whose `:from` is a pack), a whole pack, or
   a bare id only a pack can supply — and a pack not on disk yet cannot be
   ruled out as that id's home. Fetching those is part of provisioning the
   project, so a project-scoped run keeps their ops; the config's other packs
   are nobody's business on that run."
  [cfg project-ids]
  (let [wanted (set project-ids)]
    (set
     (concat
      (for [[pid proj] (:projects cfg)
            :when (contains? wanted pid)
            id (:skills proj)
            pack (packs-for-skill cfg id)
            :when pack]
        pack)
      (for [[_ a] (:agents cfg) :when (some wanted (keys (:targets a)))] (:pack a))))))

;; ---------------------------------------------------------------- pack ops

(defn git-head [dir]
  (let [{:keys [exit out]} (u/sh "git" "-C" (u/abs-path dir) "rev-parse" "HEAD")]
    (when (zero? exit) (str/trim out))))

(defn git-remote-head [uri ref]
  (let [{:keys [exit out]} (u/sh "git" "ls-remote" uri (or ref "HEAD"))]
    (when (and (zero? exit) (seq out))
      (first (str/split (str/trim out) #"\s+")))))

(defn uri-parts
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

(defn clone-run
  "How a person would clone it by hand: `gh` for a github repo when it is on
   PATH, git otherwise. Whatever this returns is what runs and what the plan
   prints — the line is a command, not a description of one. It is described
   in slots as well, so the plan can show `clone` and `obra/superpowers` as
   the two words that matter instead of one 78-column line."
  [uri ref root]
  (let [[label repo] (uri-parts uri)
        gh? (and (= "gh" label) (u/which "gh"))]
    (if gh?
      {:program "gh" :action ["repo" "clone"] :subject repo :into root
       :flags (when ref [["--branch" ref]])
       :argv (into ["gh" "repo" "clone" repo root] (when ref ["--" "--branch" ref]))}
      {:program "git" :action ["clone"] :subject uri :into root
       :flags (cond-> [["--depth" "1"]] ref (conj ["--branch" ref]))
       :argv (into ["git" "clone" "--depth" "1"]
                   (concat (when ref ["--branch" ref]) [uri root]))})))

(defn checkout-runs
  "The commands that bring a git checkout at `root` level with its remote:
   a clone when it is not there yet, a fetch and a hard reset when it is."
  [{:keys [uri ref root]} present]
  (if present
    [{:program "git" :action ["fetch"] :into root
      :flags [["--all"] ["--tags"] ["--prune"]]
      :argv ["git" "-C" root "fetch" "--all" "--tags" "--prune"]}
     {:program "git" :action ["reset"] :into root
      :subject (or ref "@{upstream}")
      :flags [["--hard"]]
      :argv ["git" "-C" root "reset" "--hard" (or ref "@{upstream}")]}]
    [(clone-run uri ref root)]))

(defn cloned-for-user?
  "A pack agentctl itself links from: placed user-wide, or the source of a
   user-wide skill, for some tool. A pack only projects use is fetched by the
   `skills` CLI. An old-format config keeps every pack — a project's bare skill
   name is resolved by scanning them. A host's config also carries
   `:consumers`, which a host places like tools; a local one never does."
  [cfg id]
  (or (not (:entity-dsl? cfg))
      (some #(and (= :skill-packs (:kind %)) (= id (:id %)) (= :all (:in %))
                  (or (not= [] (get-in % [:decl :tools]))
                      (seq (get-in cfg [:skill-packs id :consumers]))))
            (:entities cfg))
      (some #(and (= id (:from %)) (= :global (:scope %)) (or (seq (:tools %)) (seq (:consumers %))))
            (vals (:skills cfg)))
      ;; a Claude subagent is linked by agentctl, user-wide or into a project
      (some #(and (= id (:pack %)) (seq (:tools %))) (vals (:agents cfg)))))

(defn pack-ops
  "Ops that fetch or update git-backed skill packs."
  [cfg]
  (for [[id pack] (:skill-packs cfg)
        :when (and (= :git (:type pack)) (cloned-for-user? cfg id))
        :let [root (:root pack)
              present (u/exists? (str root "/.git"))
              local (when present (git-head root))
              remote (git-remote-head (:uri pack) (:ref pack))
              [label repo] (uri-parts (:uri pack))
              runs (checkout-runs pack present)
              cmds (mapv :argv runs)
              what (if (:implicit pack) "skill" "pack")]
        :when (or (not present) (and remote local (not= local remote)))]
    (plan/op {:action (if present :update :create)
              :tool :agentctl :kind :skill-packs :id id
              ;; a repository that is one skill reads as that skill, not a pack
              :shown-kind (when (:implicit pack) :skills)
              :target root
              :summary (if present
                         (str label ": update " what " `" repo "`"
                              " (" (some-> local (subs 0 7)) " -> " (some-> remote (subs 0 7)) ")")
                         (str label ": clone " what " `" repo "` -> " (u/tilde root)))
              :cmds cmds
              :runs (mapv plan/run runs)
              :diffs [{:key :revision :before local :after remote}]
              ;; a fetch that fails fails the op: nothing is owned, and
              ;; no skill is linked against a checkout that is not there
              :exec! (fn []
                       (fs/create-dirs packs-root)
                       (doseq [r runs
                               :let [{:keys [exit out err]} (apply u/sh (:argv r))]
                               :when (not (zero? exit))]
                         (throw (ex-info (str (:program r) " " (str/join " " (:action r))
                                              " exited " exit ": "
                                              (or (last (remove str/blank? (str/split-lines (str err))))
                                                  (not-empty out) "no output"))
                                         {:exit exit}))))})))
