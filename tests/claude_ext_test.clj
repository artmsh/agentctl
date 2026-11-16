(ns claude-ext-test
  "Unit tests for Claude plugin marketplaces and subagent definitions. Run by
   tests/test-plugins.sh inside a sandbox of its own (AGENTCTL_HOME), since
   they write Claude's files under that home."
  (:require [agentctl.adapters.claude :as claude]
            [agentctl.adapters.claude-plugins :as cp]
            [agentctl.config :as config]
            [agentctl.imports :as imports]
            [agentctl.state :as state]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

(defn- cfg [decl] (config/parse-config (pr-str decl) (str u/home "/agents.edn")))

(defn- clean! []
  (doseq [d [".claude" "repo" "mkt" "remote.git"]] (fs/delete-tree (str u/home "/" d))))

(defn- git! [dir & args]
  (let [r @(p/process (into ["git" "-C" dir "-c" "user.email=t@example.com" "-c" "user.name=t"] args)
                      {:out :string :err :string})]
    (is (zero? (:exit r)) (:err r))
    (str/trim (:out r))))

(defn- findings [decl]
  (set (map (juxt :level :message) (config/structural-findings (cfg decl)))))

(defn- installed! [key rows]
  (u/write-json! claude/installed-plugins-file {:version 2 :plugins {(keyword key) rows}}))

(defn- only-op [c] (let [ops (claude/plugin-ops c state/empty-state)] (is (= 1 (count ops))) (first ops)))

;; ---------------------------------------------------------------- refs

(deftest a-ref-lowers-into-the-marketplace-and-the-add-argv
  (clean!)
  (testing "github: source.ref, and owner/repo#ref on the command line"
    (let [c (cfg {:plugins {[:gh "acme/market" "lint"] {:in :all :acli [:claude] :ref "dev"}}})
          o (only-op c)]
      (is (= "dev" (get-in c [:plugins :lint :ref])))
      (is (= ["claude" "plugin" "marketplace" "add" "--scope" "user" "acme/market#dev"] (first (:cmds o))))
      (is (= {:source {:source "github" :repo "acme/market" :ref "dev"}}
             (:after (first (filter #(= :extraKnownMarketplaces (:key %)) (:diffs o))))))))
  (testing "a git URL: source.ref, and <url>#ref"
    (let [o (only-op (cfg {:plugins {[:uri "https://git.example.com/m.git" "lint"]
                                     {:in :all :acli [:claude] :ref "v1"}}}))]
      (is (= "https://git.example.com/m.git#v1" (last (first (:cmds o)))))
      (is (= {:source {:source "git" :url "https://git.example.com/m.git" :ref "v1"}}
             (:after (first (filter #(= :extraKnownMarketplaces (:key %)) (:diffs o))))))))
  (testing "no ref: the source as before"
    (let [o (only-op (cfg {:plugins {[:gh "acme/market" "lint"] {:in :all :acli [:claude]}}}))]
      (is (= "acme/market" (last (first (:cmds o)))))
      (is (= {:source {:source "github" :repo "acme/market"}}
             (:after (first (filter #(= :extraKnownMarketplaces (:key %)) (:diffs o)))))))))

(deftest a-ref-change-is-drift-and-a-same-version-switch-is-reported
  (clean!)
  (let [decl (fn [ref] {:plugins {[:gh "acme/market" "lint"] (cond-> {:in :all :acli [:claude]} ref (assoc :ref ref))}})]
    (u/write-json! claude/settings-file
                   {:extraKnownMarketplaces {:lint {:source {:source "github" :repo "acme/market" :ref "main"}}}
                    :enabledPlugins {"lint@lint" true}})
    (testing "not installed: drift, but no switch to warn about"
      (let [o (only-op (cfg (decl "dev")))]
        (is (= [{:key :extraKnownMarketplaces
                 :before {:source {:source "github" :repo "acme/market" :ref "main"}}
                 :after {:source {:source "github" :repo "acme/market" :ref "dev"}}}
                {:key :installed :before false :after true}]
               (:diffs o)))
        (is (nil? (:note o)))))
    (installed! "lint@lint" [{:scope "user" :version "1.0.0"}])
    (testing "installed: the ref is the whole diff, and the cached plugin is called out"
      (let [o (only-op (cfg (decl "dev")))]
        (is (= [:extraKnownMarketplaces] (map :key (:diffs o))))
        (is (str/includes? (:note o) "marketplace source changed"))))
    (testing "dropping the ref is a change too"
      (is (= {:source {:source "github" :repo "acme/market"}}
             (:after (first (:diffs (only-op (cfg (decl nil)))))))))
    (testing "the same ref converges"
      (is (empty? (claude/plugin-ops (cfg (decl "main")) state/empty-state))))))

(deftest a-ref-needs-a-git-marketplace
  (let [errors (fn [k v] (set (keep (fn [[l m]] (when (= :error l) m))
                                    (findings {:plugins {k (merge {:in :all :acli [:claude]} v)}}))))]
    (is (some #(str/includes? % "directory source has no revisions")
              (errors [:uri "file:///tmp/mkt" "lint"] {:ref "dev"})))
    (is (some #(str/includes? % "url source has no revisions")
              (errors [:uri "https://example.com/marketplace.json" "lint"] {:ref "dev"})))
    (is (some #(str/includes? % ":ref must be a branch, tag or commit")
              (errors [:gh "acme/market" "lint"] {:ref ""})))
    (is (some #(str/includes? % ":ref must be a branch, tag or commit")
              (errors [:gh "acme/market" "lint"] {:ref :dev})))
    (is (empty? (errors [:gh "acme/market" "lint"] {:ref "dev"})))
    (is (empty? (errors [:uri "git@git.example.com:acme/m.git" "lint"] {:ref "dev"})))))

(deftest one-marketplace-name-from-two-places-is-a-warning
  (is (some (fn [[l m]] (and (= :warn l) (str/includes? m "marketplace shared is declared from 2")))
            (findings {:plugins {[:gh "acme/market" "lint"] {:in :all :acli [:claude] :marketplace "shared" :ref "a"}
                                 [:gh "acme/market" "fmt"] {:in :all :acli [:claude] :marketplace "shared" :ref "b"}}}))))

(deftest import-keeps-the-ref-the-marketplace-and-a-directory-source
  (clean!)
  (u/write-json! claude/settings-file
                 {:extraKnownMarketplaces {:market {:source {:source "github" :repo "acme/market" :ref "dev"}}
                                           :localmkt {:source {:source "directory" :path (str u/home "/mkt")}}}
                  :enabledPlugins {"lint@market" true "lp@localmkt" true}})
  (let [rows (imports/scan-plugins)]
    (is (= {:acli [:claude] :in :all :marketplace "market" :ref "dev"}
           (get rows [:gh "acme/market" "lint"])))
    (is (= {:acli [:claude] :in :all :marketplace "localmkt"}
           (get rows [:uri "file://~/mkt" "lp"])))
    (testing "and what it imports plans nothing"
      (u/write-json! claude/installed-plugins-file
                     {:version 2 :plugins {"lint@market" [{:scope "user"}] "lp@localmkt" [{:scope "user"}]}})
      (is (empty? (claude/plugin-ops (cfg {:plugins rows}) state/empty-state))))))

(deftest imported-plugins-do-not-hide-the-legacy-sections-beside-them
  (let [discovered {:executors {:claude {:model "sonnet"}}
                    :plugins {[:gh "acme/market" "lp"] {:in :all :acli [:claude] :marketplace "acme"}}}
        text (imports/render discovered)
        back (clojure.edn/read-string text)]
    (is (not (contains? back :executors)) "legacy executors are converted, not left beside DSL rows")
    (is (= (:plugins discovered) (:plugins back)))
    (is (some #(= "sonnet" (:model %)) (:settings back)))
    (is (empty? (filter #(= :error (first %)) (findings back))))))

;; ---------------------------------------------------------------- versions

(defn- marketplace! [entry manifest]
  (let [loc (str u/home "/mkt")]
    (u/write-json! (str loc "/.claude-plugin/marketplace.json") {:name "acme" :plugins [entry]})
    (when manifest (u/write-json! (str loc "/lp/.claude-plugin/plugin.json") manifest))
    (u/write-json! cp/known-file {:acme {:source {:source "directory" :path loc} :installLocation loc}})
    loc))

(deftest the-offered-version-is-the-one-claude-installs
  ;; plugin.json, else the marketplace entry, else the commit: Claude's order
  (clean!)
  (marketplace! {:name "lp" :source "./lp" :version "2.0.0"} {:name "lp" :version "3.0.0"})
  (is (= "3.0.0" (:version (cp/offered "acme" "lp"))))
  (marketplace! {:name "lp" :source "./lp" :version "2.0.0"} {:name "lp"})
  (is (= "2.0.0" (:version (cp/offered "acme" "lp"))))
  (let [loc (marketplace! {:name "lp" :source "./lp"} {:name "lp"})]
    (git! loc "init" "-q")
    (git! loc "add" "-A")
    (git! loc "commit" "-qm" "c")
    (let [sha (git! loc "rev-parse" "HEAD")]
      (is (= {:version (subs sha 0 12) :revision sha} (cp/offered "acme" "lp")))))
  (testing "a plugin kept in another repository has no version until it is fetched"
    (clean!)
    (marketplace! {:name "lp" :source {:source "github" :repo "acme/lp"} :version "1.0.0"} nil)
    (is (nil? (:version (cp/offered "acme" "lp")))))
  (is (nil? (cp/offered "acme" "nope")))
  (is (nil? (cp/offered "unknown" "lp"))))

(deftest a-version-diff-says-what-the-row-asks-for
  (let [rec {:version "1.0.0"}
        fresh {:version "1.1.0" :revision "aaaaaaaaaaaa"}]
    (is (= {:key :version :before "1.0.0" :after "1.1.0"} (cp/version-diff :latest rec fresh nil)))
    (is (= {:key :version :before "1.0.0" :after "1.1.0"} (cp/version-diff :latest rec fresh "aaaaaaaaaaaa")))
    (is (= {:key :version :before "1.0.0" :after "latest @ bbbbbbb"}
           (cp/version-diff :latest rec {:version "1.0.0" :revision "aaaaaaa"} "bbbbbbbbbbbb"))
        "a remote that moved offers what is not fetched yet")
    (is (nil? (cp/version-diff :latest rec {:version "1.0.0" :revision "a"} "a")))
    (is (nil? (cp/version-diff :latest rec {:version nil :revision "a"} nil)) "unknown is not drift")
    (is (= {:key :version :before "1.0.0" :after "0.9.0"} (cp/version-diff "0.9.0" rec fresh nil)))
    (is (nil? (cp/version-diff "1.0.0" rec fresh nil)) "a pin that holds needs nothing")
    (is (nil? (cp/version-diff nil rec fresh nil)) "no :version, presence only")
    (is (nil? (cp/version-diff :latest nil fresh nil)) "not installed is the install's drift")
    (is (str/includes? (cp/pin-note "0.9.0" "acme" fresh) "offers 1.1.0"))
    (is (nil? (cp/pin-note "1.1.0" "acme" fresh)))))

(deftest the-steps-follow-what-is-installed
  (let [s #(map (fn [argv] (str/join " " (drop 2 argv)))
                (cp/steps (merge {:key "lp@acme" :cli-scope "user"} %)))]
    (is (= ["install --scope user -y lp@acme"] (s {:installed? false})))
    (is (= ["uninstall --scope user -y lp@acme" "install --scope user -y lp@acme"]
           (s {:installed? true :switched? true :behind? true})))
    (is (= ["update --scope user -y lp@acme"] (s {:installed? true :behind? true})))
    (is (= [] (s {:installed? true :current? true :behind? false})))
    (is (= ["install --scope user -y lp@acme"] (s {:installed? true})))))

(deftest the-remote-revision-of-a-tag-is-its-commit
  (clean!)
  (let [work (str u/home "/repo") bare (str u/home "/remote.git")]
    (fs/create-dirs work)
    (git! work "init" "-q")
    (spit (str work "/f") "x")
    (git! work "add" "-A")
    (git! work "commit" "-qm" "c")
    (git! work "tag" "-a" "v1" "-m" "annotated")
    (git! work "clone" "-q" "--bare" work bare)
    (let [sha (git! work "rev-parse" "HEAD")
          known! #(u/write-json! cp/known-file {:acme {:source (merge {:source "git" :url (str "file://" bare)} %)
                                                       :installLocation (str u/home "/mkt")}})]
      (known! {:ref "v1"})
      (is (= sha (cp/remote-revision "acme")))
      (known! {})
      (is (= sha (cp/remote-revision "acme")))
      (known! {:ref sha})
      (is (nil? (cp/remote-revision "acme")) "a commit cannot move")
      (u/write-json! cp/known-file {:acme {:source {:source "directory" :path work} :installLocation work}})
      (is (nil? (cp/remote-revision "acme")) "a directory is read live"))))

(deftest version-and-build-have-one-shape
  (let [errors (fn [v] (set (keep (fn [[l m]] (when (= :error l) m))
                                  (findings {:plugins {[:gh "acme/market" "lp"] (merge {:in :all :acli [:claude]} v)}}))))]
    (is (empty? (errors {:version :latest :build "make"})))
    (is (empty? (errors {:version "1.2.0"})))
    (is (some #(str/includes? % ":version must be :latest") (errors {:version :newest})))
    (is (some #(str/includes? % ":version must be :latest") (errors {:version ""})))
    (is (some #(str/includes? % ":build is a shell command") (errors {:build ["make"]})))))

(let [{:keys [fail error]} (run-tests 'claude-ext-test)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
