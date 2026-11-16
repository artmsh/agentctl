(ns claude-ext-test
  "Unit tests for Claude plugin marketplaces and subagent definitions. Run by
   tests/test-plugins.sh inside a sandbox of its own (AGENTCTL_HOME), since
   they write Claude's files under that home."
  (:require [agentctl.adapters.claude :as claude]
            [agentctl.adapters.claude-agents :as ca]
            [agentctl.adapters.claude-plugins :as cp]
            [agentctl.adapters.claude-sources :as cs]
            [agentctl.config :as config]
            [agentctl.core :as core]
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

(deftest one-marketplace-name-from-two-places-is-an-error
  (is (some (fn [[l m]] (and (= :error l) (str/includes? m "marketplace shared is declared from 2")))
            (findings {:plugins {[:gh "acme/market" "lint"] {:in :all :acli [:claude] :marketplace "shared" :ref "a"}
                                 [:gh "acme/market" "fmt"] {:in :all :acli [:claude] :marketplace "shared" :ref "b"}}})))
  (is (not-any? (fn [[l m]] (and (= :error l) (str/includes? m "marketplace shared")))
                (findings {:plugins {[:uri "https://github.com/acme/market" "lint"] {:in :all :acli [:claude] :marketplace "shared"}
                                     [:uri "https://github.com/acme/market.git" "fmt"] {:in :all :acli [:claude] :marketplace "shared"}}}))
      "one remote spelled with and without .git"))

(deftest one-marketplace-name-from-two-places-is-flagged-in-the-plan
  (clean!)
  (let [repo (str u/home "/repo")]
    (fs/create-dirs (str repo "/.git"))
    (let [ops (claude/plugin-ops (cfg {:plugins {[:gh "acme/market" "lint"] {:in :all :acli [:claude] :marketplace "shared"}
                                                 [:gh "acme/market" "fmt"] {:in [repo] :at :repo :acli [:claude]
                                                                             :marketplace "shared" :ref "dev"}}})
                                 state/empty-state)]
      (is (= 2 (count ops)))
      (is (every? #(str/includes? (str (:note %)) "declares marketplace shared from more than one source") ops)))))

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

;; ---------------------------------------------------------------- sources as Claude reads them

(deftest a-remote-url-is-the-source-claude-makes-of-it
  (is (= {:source "git" :url "https://github.com/o/r.git"} (cs/remote "https://github.com/o/r")))
  (is (= {:source "git" :url "https://github.com/o/r.git"} (cs/remote "https://github.com/o/r/")))
  (is (= {:source "git" :url "https://github.com/o/r.git"} (cs/remote "https://github.com/o/r.git")))
  (is (= {:source "git" :url "https://gitlab.com/g/sub/p.git"} (cs/remote "https://gitlab.com/g/sub/p")))
  (is (= {:source "git" :url "https://git.example.com/m.git"} (cs/remote "https://git.example.com/m.git")))
  (is (= {:source "git" :url "https://dev.azure.com/o/p/_git/r"} (cs/remote "https://dev.azure.com/o/p/_git/r")))
  (is (= {:source "git" :url "git@example.com:o/r"} (cs/remote "git@example.com:o/r")))
  (is (= {:source "url" :url "https://example.com/m"} (cs/remote "https://example.com/m"))
      "any other host serves a marketplace.json")
  (is (= {:source "url" :url "https://example.com/m.json"} (cs/remote "https://example.com/m.json")))
  (testing "the row lowers to it, and a ref only goes on git"
    (clean!)
    (let [after #(:after (first (filter (fn [d] (= :extraKnownMarketplaces (:key d))) (:diffs (only-op (cfg %))))))]
      (is (= {:source {:source "git" :url "https://github.com/o/r.git" :ref "v1"}}
             (after {:plugins {[:uri "https://github.com/o/r" "lp"] {:in :all :acli [:claude] :ref "v1"}}})))
      (is (= {:source {:source "url" :url "https://example.com/m"}}
             (after {:plugins {[:uri "https://example.com/m" "lp"] {:in :all :acli [:claude]}}})))
      (is (some #(str/includes? % "url source has no revisions")
                (map second (findings {:plugins {[:uri "https://example.com/m" "lp"]
                                                 {:in :all :acli [:claude] :ref "v1"}}})))))))

(deftest settings-the-row-does-not-declare-survive
  (clean!)
  (u/write-json! claude/settings-file
                 {:extraKnownMarketplaces {:lp {:source {:source "git" :url "https://github.com/o/r"}
                                                :autoUpdate true}}
                  :enabledPlugins {"lp@lp" true}})
  (installed! "lp@lp" [{:scope "user" :version "1.0.0"}])
  (let [o (only-op (cfg {:plugins {[:uri "https://github.com/o/r" "lp"] {:in :all :acli [:claude]}}}))]
    (is (= {:source {:source "git" :url "https://github.com/o/r.git"} :autoUpdate true}
           (:after (first (:diffs o))))
        "the entry is respelled the way the CLI writes it, autoUpdate kept")
    (is (nil? (:note o)) "a URL with or without .git is one remote: no reinstall")
    (is (not-any? #(= "uninstall" (nth % 2 nil)) (:cmds o)))))

(deftest a-project-marketplace-must-match-the-user-settings-source
  (clean!)
  (let [repo (str u/home "/repo")]
    (fs/create-dirs (str repo "/.git"))
    (u/write-json! claude/settings-file
                   {:extraKnownMarketplaces {:lp {:source {:source "github" :repo "o/r"}}}})
    (let [note #(:note (only-op (cfg {:plugins {[:gh "o/r" "lp"] (merge {:in [repo] :at :repo :acli [:claude]} %)}})))]
      (is (nil? (note {})) "the same source: Claude adds it to the project")
      (is (str/includes? (note {:ref "dev"}) "Claude adds it to a project only from that source")))))

;; ---------------------------------------------------------------- agents

(deftest agents-lower-to-one-link-per-placement
  (clean!)
  (let [pack (str u/home "/repo")
        _ (fs/create-dirs (str pack "/agents"))
        c (cfg {:skill-packs {[:uri (str "file://" pack)] {:alias 'team}}
                :agents {['team ["a" "b"]] {:in :all}
                         ['team "c"] {:in :all :acli [:codex]}}})
        ps (ca/placements c)]
    (is (= #{:a :b} (set (map :id ps))) "a row for no Claude places nothing")
    (is (= (str pack "/agents/a.md") (:source (first (filter #(= :a (:id %)) ps)))))
    (is (= (str u/home "/.claude/agents/a.md") (:path (first (filter #(= :a (:id %)) ps)))))
    (is (some #(= [:warn [:agents :c]] [(:level %) (:where %)])
              (config/structural-findings c))))
  (testing "a pack placed nowhere itself names the tools that use it"
    (let [c (cfg {:skill-packs {[:uri (str "file://" u/home "/remote.git")] {:alias 'team :type :git}}
                  :agents {['team "a"] {:in :all}}})
          pack-op (first (filter #(= :skill-packs (:kind %))
                                 (core/build-plan c state/empty-state {:tools #{:claude} :kinds #{:agents}})))]
      (is (= ["claude-code"] (get-in pack-op [:display :agents])))))
  (is (thrown-with-msg? Exception #"names undefined pack"
                        (cfg {:agents {['nope "a"] {:in :all}}})))
  (is (thrown-with-msg? Exception #"invalid :agents key"
                        (cfg {:skill-packs {[:uri "file:///x"] {:alias 'team}}
                              :agents {[:gh "o/r"] {:in :all}}}))))

(let [{:keys [fail error]} (run-tests 'claude-ext-test)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
