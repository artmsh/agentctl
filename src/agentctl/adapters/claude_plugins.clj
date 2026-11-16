(ns agentctl.adapters.claude-plugins
  "The version side of Claude Code plugins: what the CLI has installed, what
   a marketplace on disk offers, whether its remote has moved on, and the
   `claude plugin` steps that close the gap. `agentctl.adapters.claude` plans
   the marketplace registration and settings around these.

   Claude computes a plugin's version itself, and agentctl reads it the same
   way (checked against Claude Code 2.1): a plugin kept inside its marketplace
   takes `.claude-plugin/plugin.json`'s version, else the marketplace entry's,
   else the first 12 hex digits of the marketplace commit. `plugin update`
   moves to whatever the catalog on disk offers, downgrades included, and does
   nothing at an unchanged version; the catalog only moves on `marketplace
   update`."
  (:require [agentctl.extensions :as extensions]
            [agentctl.plan :as plan]
            [agentctl.scope :as scope]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def plugins-dir (str u/home "/.claude/plugins"))
(def installed-file (str plugins-dir "/installed_plugins.json"))
(def known-file (str plugins-dir "/known_marketplaces.json"))
(def built-dir
  "Stamps of successful `:build` runs, one per install path. Outside Claude's
   cache, so the build neither adds files there nor is lost with it."
  (str u/home "/.agents/plugins/.built"))

;; ---------------------------------------------------------------- reading

(defn install-record
  "The CLI's install row for `key` at `cli-scope` — for a project or local
   scope, at `project-path` — or nil."
  [key cli-scope project-path]
  (let [canon #(str (fs/canonicalize (u/abs-path %)))
        here (some-> project-path canon)]
    (some (fn [{s :scope p :projectPath :as row}]
            (when (and (= cli-scope s) (or (= "user" cli-scope) (= here (some-> p canon))))
              row))
          (get-in (u/read-json installed-file) [:plugins (keyword key)]))))

(defn marketplace
  "Claude's registration of marketplace `mname`: its source and where the
   catalog sits on disk. One per name, whatever scope declared it."
  [mname]
  (get (u/read-json known-file) (keyword mname)))

(defn- git-out [dir & args]
  (let [{:keys [exit out]} (u/sh-env {:GIT_TERMINAL_PROMPT "0" :GIT_SSH_COMMAND "ssh -o BatchMode=yes"}
                                     (into ["git" "-C" dir] args))]
    (when (zero? exit) out)))

(defn revision
  "The commit a marketplace on disk is at: its clone's HEAD, or the snapshot
   id Claude keeps beside a catalog it fetched without git."
  [loc]
  (or (when (u/exists? (str loc "/.git")) (git-out loc "rev-parse" "HEAD"))
      (some-> (u/slurp-safe (str loc "/.gcs-sha")) str/trim not-empty)))

(defn offered
  "What marketplace `mname` on disk offers for `plugin`: `{:version :revision}`,
   or nil when it is not on disk or does not list the plugin. `:version` is
   nil for a plugin kept in another repository — its version is known only
   once that is fetched."
  [mname plugin]
  (when-let [loc (:installLocation (marketplace mname))]
    (let [entry (some #(when (= plugin (:name %)) %)
                      (:plugins (u/read-json (str loc "/.claude-plugin/marketplace.json"))))
          rev (revision loc)]
      (when entry
        {:revision rev
         :version (when (string? (:source entry))
                    (or (:version (u/read-json (str loc "/" (:source entry) "/.claude-plugin/plugin.json")))
                        (:version entry)
                        (some-> rev (subs 0 (min 12 (count rev))))))}))))

(defn- remote-url [{:keys [source]}]
  (case (:source source)
    "github" (str "https://github.com/" (:repo source) ".git")
    "git" (:url source)
    nil))

(defn remote-revision
  "The commit a git marketplace's source points at now, asked of the remote
   and nothing fetched: through the clone's own origin when there is one, so
   the transport is the one Claude chose. nil for a directory (Claude reads it
   live), a pinned commit (it cannot move), or a remote that does not answer."
  [mname]
  (let [{:keys [source installLocation] :as m} (marketplace mname)
        ref (:ref source)
        url (remote-url m)]
    (cond
      (nil? url) nil
      (and ref (re-matches #"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}" ref)) nil
      :else
      (let [;; an annotated tag lists the tag object; `<ref>^{}` asks for the
            ;; commit it points at as well
            patterns (if ref [ref (str ref "^{}")] ["HEAD"])
            out (if (u/exists? (str installLocation "/.git"))
                  (apply git-out installLocation "ls-remote" "origin" patterns)
                  (apply git-out "." "ls-remote" url patterns))
            rows (for [line (some-> out str/split-lines) :let [[sha r] (str/split line #"\s+")] :when r] [r sha])
            refs (into {} rows)]
        (or (some #(get refs %) (if ref
                                  [(str "refs/tags/" ref "^{}") (str "refs/heads/" ref) (str "refs/tags/" ref) ref]
                                  ["HEAD"]))
            (second (first rows)))))))

;; ---------------------------------------------------------------- planning

(defn- short-sha [s] (subs s 0 (min 7 (count s))))

(defn wanted-version
  "The version `:version` asks for given what is on disk and what the remote
   says, or nil when it cannot be told. A marketplace whose remote has moved
   offers something not fetched yet: `latest @ <commit>`."
  [want offer remote]
  (cond
    (string? want) want
    (not= :latest want) nil
    (and remote (:revision offer) (not= remote (:revision offer))) (str "latest @ " (short-sha remote))
    :else (:version offer)))

(defn version-diff
  "The `version` diff an installed plugin owes `:version`, or nil."
  [want rec offer remote]
  (when (and want rec)
    (let [after (wanted-version want offer remote)]
      (when (and after (not= after (:version rec)))
        {:key :version :before (:version rec) :after after}))))

(defn pin-note
  "A pinned version the catalog on disk does not offer: refreshing may not
   help, and `apply!` fails rather than installing something else."
  [want mname offer]
  (when (and (string? want) (:version offer) (not= want (:version offer)))
    (str "⚠ marketplace " mname " offers " (:version offer)
         (when-let [r (:revision offer)] (str " at " (short-sha r)))
         ", not " want " — apply! fails unless the marketplace carries it (pin it with :ref)")))

(defn refresh-cmd [mname] ["claude" "plugin" "marketplace" "update" mname])

(defn steps
  "The `claude plugin` commands that follow `marketplace add` (and, for a row
   with `:version`, the refresh): an install, an update, or — after a source
   switch, when the cached copy must go — an uninstall and an install."
  [{:keys [key cli-scope installed? switched? behind? current?]}]
  (let [install ["claude" "plugin" "install" "--scope" cli-scope "-y" key]]
    (cond
      (not installed?) [install]
      switched? [["claude" "plugin" "uninstall" "--scope" cli-scope "-y" key] install]
      current? []
      behind? [["claude" "plugin" "update" "--scope" cli-scope "-y" key]]
      :else [install])))

;; ---------------------------------------------------------------- applying

(defn- refuse-unoffered! [want mname offer key]
  (when-let [note (pin-note want mname offer)]
    (throw (ex-info (str/replace note #"^⚠ " "") {:plugin key}))))

(defn converge!
  "Bring the install in line, deciding on what is on disk at execution time:
   refresh the catalog when a version is asked for, refuse a pinned version it
   does not offer before anything is installed, run `steps`, and fail when the
   install does not land on the wanted version."
  [{:keys [run key cli-scope project-path mname plugin want switched?]}]
  (let [record #(install-record key cli-scope project-path)
        rec (record)
        _ (when want (run (refresh-cmd mname)))
        offer (offered mname plugin)
        target (wanted-version want offer nil)
        current? (boolean (and want rec target (= target (:version rec)) (not switched?)))]
    (when-not current? (refuse-unoffered! want mname offer key))
    (doseq [argv (steps {:key key :cli-scope cli-scope :installed? (some? rec) :switched? switched?
                         :current? current? :behind? (and want (not current?))})]
      (run argv))
    (let [after (record)]
      (when-not after
        (throw (ex-info (str key " is not in Claude's install record after `claude plugin install`") {:plugin key})))
      (when (and target (not= target (:version after)))
        (throw (ex-info (str "claude left " key " at " (:version after) ", not " target) {:plugin key}))))))

;; ---------------------------------------------------------------- :build

(defn stamp-file [install-path] (str built-dir "/" (scope/digest install-path)))

(defn- stamp
  "What a build is a build of: the install path, the commit it came from and
   the command."
  [rec build]
  (str (:installPath rec) "\n" (:gitCommitSha rec) "\n" build "\n"))

(defn built?
  "Has `build` run in the install `rec` names, as it is now?"
  [rec build]
  (boolean (and rec (= (u/slurp-safe (stamp-file (:installPath rec))) (stamp rec build)))))

(defn build-op
  "Run a plugin's `:build` in its install path: once per new install path, and
   after an install op that may move it. The path is read when the op runs —
   a new install's is not known before. The op carries the plugin's id, so a
   failed build fails the placement and agentctl claims no ownership of it."
  [{:keys [id plugin project key cli-scope]} installing?]
  (let [build (:build plugin)
        rec (install-record key cli-scope (:path project))]
    (when (and build (or installing? (not (built? rec build))))
      (plan/op {:action (if (and rec (u/exists? (stamp-file (:installPath rec)))) :update :create)
                :tool :claude :kind :plugins :id id
                :project (some-> (:id project) keyword) :project-path (:path project)
                :target (if (and rec (not installing?))
                          (:installPath rec)
                          (str plugins-dir "/cache/" (:marketplace plugin) "/" (:plugin plugin)))
                :category :cmd
                ;; a failed build fails apply!, but the install it follows
                ;; stands and stays owned: a dropped row still uninstalls it.
                ;; No stamp is left, so the next run builds again.
                :owns []
                :summary (str "build `" build "` in "
                              (if (and rec (not installing?)) (u/tilde (:installPath rec)) "the new install path"))
                :cmds [["sh" "-c" build]]
                :exec! (fn []
                         (let [rec (install-record key cli-scope (:path project))]
                           (when-not rec
                             (throw (ex-info (str "nothing to build: " key " is not installed") {:plugin key})))
                           ;; two placements can share one install path
                           (when-not (built? rec build)
                             (extensions/run-build! (:installPath rec) build (stamp-file (:installPath rec))
                                                    #(stamp rec build)))))}))))
