(ns agentctl.extensions
  "Extension sources: fetched once, built once, linked by every CLI that
   loads them.

   An extension is code a CLI runs, so getting it onto disk is two steps a
   skill pack never needs: the checkout, and — for one that ships sources
   rather than artifacts — a build inside it. Both happen here, ahead of the
   adapters, which only link the result into place."
  (:require [agentctl.plan :as plan]
            [agentctl.sources :as sources]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def root-dir (str u/home "/.agents/extensions"))

(defn stamp-file
  "Where the last successful build of extension `id` is recorded. Outside the
   checkout, so it neither dirties the tree nor is lost to a hard reset."
  [id]
  (str root-dir "/.built/" (name id)))

(defn- stamp
  "What a build is a build of: the revision and the command that produced it."
  [rev build]
  (str rev "\n" build "\n"))

(defn built?
  "Has `:build` already run against what is on disk now? An extension with no
   build is ready as soon as it is there."
  [{:keys [id root build]}]
  (or (nil? build)
      (= (u/slurp-safe (stamp-file id)) (stamp (sources/git-head root) build))))

(defn- owns [ext] (mapv #(vector % :extensions (:id ext)) (sort (:for-tools ext))))

(defn- exec
  "Run one command, failing loudly: an extension whose fetch or build broke
   must not be linked into a CLI as if it had worked."
  [what dir argv]
  (let [{:keys [exit out err]} (if dir (u/sh-in dir argv) (u/sh argv))]
    (when-not (zero? exit)
      (throw (ex-info (str what " failed (exit " exit ")"
                           (when-let [tail (->> (str/split-lines (str out "\n" err))
                                                (remove str/blank?) (take-last 5) seq)]
                             (str ": " (str/join " | " tail))))
                      {:argv argv :exit exit})))))

(defn- fetch-op [ext present local remote]
  (let [{:keys [id uri root]} ext
        [label repo] (sources/uri-parts uri)
        runs (sources/checkout-runs ext present)
        cmds (mapv :argv runs)]
    (plan/op {:action (if present :update :create)
              :tool :agentctl :kind :extensions :id id
              :owns (owns ext)
              :target root
              :summary (if present
                         (str label ": update extension `" repo "`"
                              " (" (some-> local (subs 0 7)) " -> " (some-> remote (subs 0 7)) ")")
                         (str label ": clone extension `" repo "` -> " (u/tilde root)))
              :cmds cmds
              :runs (mapv plan/run runs)
              :diffs [{:key :revision :before local :after remote}]
              :exec! (fn []
                       (fs/create-dirs root-dir)
                       (doseq [c cmds] (exec (if present "update" "clone") nil c)))})))

(defn- build-op [ext]
  (let [{:keys [id root build]} ext
        rebuilt? (u/exists? (stamp-file id))]
    (plan/op {:action (if rebuilt? :update :create)
              :tool :agentctl :kind :extensions :id id
              :owns (owns ext)
              ;; the stamp, not the checkout: this op and the fetch are two
              ;; changes, and one target would read as one
              :target (stamp-file id)
              :summary (str "build `" build "` in " (u/tilde root))
              :cmds [["sh" "-c" build]]
              :exec! (fn []
                       (when-not (fs/directory? root)
                         (throw (ex-info (str "nothing to build: " (u/tilde root) " is not there") {:root root})))
                       ;; a build that fails halfway leaves no claim behind it
                       (fs/delete-if-exists (stamp-file id))
                       (exec "build" root ["sh" "-c" build])
                       (u/write-text! (stamp-file id) (stamp (sources/git-head root) build)))})))

(defn source-ops
  "Ops that fetch, update and build the extensions the `selected` tools load."
  [cfg selected]
  (mapcat
   (fn [[id ext]]
     (let [{:keys [type root uri ref build]} ext
           git? (= :git type)
           present (if git? (u/exists? (str root "/.git")) (fs/directory? root))
           local (when (and git? present) (sources/git-head root))
           remote (when git? (sources/git-remote-head uri ref))
           fetch? (and git? (or (not present) (and remote local (not= local remote))))]
       (cond
         (and (not git?) (not present))
         [(plan/op {:action :noop :warn true :tool :agentctl :kind :extensions :id id :target root
                    :summary (str "extension source " (u/tilde root) " not found — not installed")})]

         :else
         (remove nil?
                 [(when fetch? (fetch-op ext present local remote))
                  ;; a fetch moves the revision under the build, so it is
                  ;; planned with it rather than discovered on the next run
                  (when (and build (or fetch? (not (built? ext))))
                    (build-op ext))]))))
   (sort-by key (filter (fn [[_ e]] (some selected (:for-tools e))) (:extensions cfg)))))

(defn ready?
  "Can a CLI be pointed at this extension — is it on disk and built?"
  [ext]
  (and (fs/directory? (:root ext)) (built? ext)))
