(ns claude-ext-test
  "Unit tests for Claude plugin marketplaces and subagent definitions. Run by
   tests/test-plugins.sh inside a sandbox of its own (AGENTCTL_HOME), since
   they write Claude's files under that home."
  (:require [agentctl.adapters.claude :as claude]
            [agentctl.config :as config]
            [agentctl.imports :as imports]
            [agentctl.state :as state]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

(defn- cfg [decl] (config/parse-config (pr-str decl) (str u/home "/agents.edn")))

(defn- clean! []
  (doseq [d [".claude" "repo"]] (fs/delete-tree (str u/home "/" d))))

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

(let [{:keys [fail error]} (run-tests 'claude-ext-test)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
