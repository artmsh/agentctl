(ns statusline-test
  "Unit tests for the shared status-line config. Run by tests/test-statusline.sh
   inside its sandbox (AGENTCTL_HOME), with tests/statusline-stub standing in
   for the renderer."
  (:require [agentctl.config :as config]
            [agentctl.core :as core]
            [agentctl.imports :as imports]
            [agentctl.plan :as plan]
            [agentctl.state :as state]
            [agentctl.statusline :as sl]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

(def stub (str (fs/absolutize (fs/path (fs/parent *file*) "statusline-stub"))))

(defn- cfg [decl] (config/parse-config (pr-str {:statusline decl}) (str u/home "/agents.edn")))

(deftest edn-spells-the-json-in-kebab-case
  (let [{:keys [data text clashes]}
        (sl/normalize {:bin "/x/agent-statusline"
                       :agents {:pi {:segments [:model :cost] :show-pacing false}
                                :cc {:color nil}}
                       :defaults {:critical-percent 80 :context-style :full
                                  :segments [:model 'context "branch"] :separator " · "}})]
    (testing "keys translate, enum keywords become strings, nil stays null"
      (is (= {"defaults" {"segments" ["model" "context" "branch"] "separator" " · "
                          "context_style" "full" "critical_percent" 80}
              "agents" {"cc" {"color" nil} "pi" {"segments" ["model" "cost"] "show_pacing" false}}}
             data))
      (is (nil? clashes)))
    (testing ":bin is agentctl's and never written"
      (is (not (str/includes? text "bin"))))
    (testing "written in the renderer's documented order, whatever the EDN order"
      (is (< (str/index-of text "\"defaults\"") (str/index-of text "\"agents\"")))
      (is (< (str/index-of text "\"segments\"") (str/index-of text "\"separator\"")
             (str/index-of text "\"context_style\"") (str/index-of text "\"critical_percent\"")))
      (is (< (str/index-of text "\"cc\"") (str/index-of text "\"pi\"")))
      (is (str/ends-with? text "}\n")))))

(deftest an-override-lands-under-its-own-agent-only
  (let [{:keys [data]} (sl/normalize {:defaults {:color :auto}
                                      :agents {:pi {:context-style :percent}
                                               :awl {:warning-percent 10}}})]
    (is (= {"color" "auto"} (get data "defaults")))
    (is (= {"context_style" "percent"} (get-in data ["agents" "pi"])))
    (is (= {"warning_percent" 10} (get-in data ["agents" "awl"])))
    (is (= #{"pi" "awl"} (set (keys (get data "agents")))))))

(deftest unknown-keys-pass-through-for-the-renderer-to-judge
  (is (= {"defaults" {"colour" "auto"} "extra" 1}
         (:data (sl/normalize {:defaults {:colour :auto} :extra 1})))))

(deftest two-spellings-of-one-key-are-an-error
  (let [n (sl/normalize {:defaults {:context-style :full :context_style :compact}})]
    (is (= [{:where ["defaults" "context_style"] :keys [:context-style :context_style]}] (:clashes n)))
    (is (some #(and (= :error (:level %)) (str/includes? (:message %) "keep one"))
              (config/structural-findings (cfg {:defaults {:context-style :full :context_style :compact}}))))
    (is (empty? (sl/inventory (cfg {:defaults {:context-style :full :context_style :compact}}))))))

(deftest a-statusline-that-is-not-a-map-is-an-error
  (is (some #(and (= :error (:level %)) (= [:statusline] (:where %)))
            (config/structural-findings (cfg [:model])))))

(deftest the-file-follows-xdg-but-never-leaves-the-sandbox
  (let [home u/home]
    (testing "an XDG base inside the sandbox is used"
      (is (= (str home "/xdg/agent-statusline/config.json")
             (sl/config-path {"AGENTCTL_HOME" home "XDG_CONFIG_HOME" (str home "/xdg/")}))))
    (testing "one outside it is not: a scratch run must not write the real home"
      (is (= (str home "/.config/agent-statusline/config.json")
             (sl/config-path {"AGENTCTL_HOME" home "XDG_CONFIG_HOME" "/elsewhere/.config"}))))
    (testing "without a sandbox XDG wins"
      (is (= "/elsewhere/.config/agent-statusline/config.json"
             (sl/config-path {"XDG_CONFIG_HOME" "/elsewhere/.config"}))))
    (testing "empty and relative XDG are no base directory"
      (is (= (str home "/.config/agent-statusline/config.json") (sl/config-path {"XDG_CONFIG_HOME" ""})))
      (is (= (str home "/.config/agent-statusline/config.json") (sl/config-path {"XDG_CONFIG_HOME" "rel"}))))
    (testing "`..` cannot climb out of the sandbox, and the stored path is normalized"
      (is (= (str home "/.config/agent-statusline/config.json")
             (sl/config-path {"AGENTCTL_HOME" home "XDG_CONFIG_HOME" (str home "/../outside")})))
      (is (= (str home "/y/agent-statusline/config.json")
             (sl/config-path {"AGENTCTL_HOME" home "XDG_CONFIG_HOME" (str home "/./x/../y")}))))
    (testing "a symlinked spelling of a directory inside the sandbox is inside it"
      (let [real (str home "/xdg-real")
            link (str (fs/create-temp-dir) "/xdg-link")]
        (fs/create-dirs real)
        (fs/create-sym-link link real)
        (is (= (str link "/agent-statusline/config.json")
               (sl/config-path {"AGENTCTL_HOME" home "XDG_CONFIG_HOME" link})))
        (is (sl/same-file? (str link "/agent-statusline/config.json")
                           (str real "/agent-statusline/config.json")))))
    (testing "an empty AGENT_STATUSLINE_CONFIG is a path the renderer fails on, and said so"
      (let [m (:message (sl/env-finding {"AGENT_STATUSLINE_CONFIG" ""} (sl/config-path {})))]
        (is (str/includes? m "set but empty"))
        (is (not (str/includes? m "reads  —")))))
    (testing "the renderer's own lookup: AGENT_STATUSLINE_CONFIG, then XDG as given"
      (is (= "/c.json" (sl/renderer-path {"AGENT_STATUSLINE_CONFIG" "/c.json" "XDG_CONFIG_HOME" "/x"})))
      (is (= "/agent-statusline/config.json" (sl/renderer-path {"XDG_CONFIG_HOME" ""})))
      (is (= (str home "/.config/agent-statusline/config.json") (sl/renderer-path {}))))))

(deftest check-asks-the-renderer-and-says-when-it-cannot
  (testing "valid and invalid, by exit status"
    (is (= :ok (:status (sl/check (sl/normalize {:bin stub :defaults {:segments [:model]}})))))
    (let [r (sl/check (sl/normalize {:bin stub :defaults {:segments [:model :model]}}))]
      (is (= :invalid (:status r)))
      (is (str/includes? (:message r) "DuplicateSegment"))))
  (testing "no binary is unknown, never a pass"
    (let [r (sl/check (sl/normalize {:bin (str u/home "/nowhere/agent-statusline")}))]
      (is (= :unknown (:status r)))
      (is (str/includes? (:message r) "not validated"))))
  (testing "a binary that accepts anything is not a renderer that checks"
    (let [yes (str u/home "/yes-agent-statusline")]
      (spit yes "#!/bin/sh\nexit 0\n")
      (fs/set-posix-file-permissions yes "rwxr-xr-x")
      (let [r (sl/check (sl/normalize {:bin yes :defaults {:segments [:model]}}))]
        (is (= :unknown (:status r)))
        (is (str/includes? (:message r) "accepts a config with an unknown key")))))
  (testing "one that hangs has timed out, which says nothing about --check"
    (let [slow (str u/home "/slow-agent-statusline")]
      (spit slow "#!/bin/sh\nsleep 5\n")
      (fs/set-posix-file-permissions slow "rwxr-xr-x")
      (binding [sl/*timeout-ms* 300]
        (let [r (sl/check (sl/normalize {:bin slow :defaults {:segments [:context]}}))]
          (is (= :unknown (:status r)))
          (is (str/includes? (:message r) "timed out"))
          (is (not (str/includes? (:message r) "does not implement")))))))
  (testing "a binary without --check rejects even {}: unknown, not invalid"
    (let [old (str u/home "/old-agent-statusline")]
      (spit old "#!/bin/sh\necho 'agent-statusline: UnknownArgument' >&2\nexit 1\n")
      (fs/set-posix-file-permissions old "rwxr-xr-x")
      (let [r (sl/check (sl/normalize {:bin old :defaults {:segments [:model]}}))]
        (is (= :unknown (:status r)))
        (is (str/includes? (:message r) "does not implement --check"))))))

(deftest findings-sit-in-their-own-section
  (let [c (cfg {:bin stub :defaults {:warning-percent 90 :critical-percent 50}})
        structural (config/structural-findings c)]
    (is (some #(and (= [:statusline :config] (:where %)) (= :error (:level %))
                    (str/includes? (:message %) "InvalidThresholds")) (sl/findings c)))
    (testing "the renderer's verdict is about this machine, not part of the structural check"
      (is (not-any? #(= [:statusline :config] (:where %)) structural)))
    (is (not-any? #(str/includes? (:message %) "unknown top-level key") structural))))

(deftest a-null-override-previews-as-a-field
  (let [target (sl/config-path)
        c (cfg {:bin stub :defaults {:color :auto} :agents {:cc {:color nil :context-style :full}}})
        op #(first (sl/plan-ops c state/empty-state {}))
        render #(plan/render-op (op) {})]
    (fs/delete-if-exists target)
    (binding [plan/*color* false]
      (testing "a key set to null is listed, as the plan spells null"
        (is (str/includes? (render) "+ cc\n          + color: ∅")))
      (testing "the data stays data: JSON gets null, not a label"
        (let [after (:after (first (:diffs (op))))]
          (is (contains? (get-in after ["agents" "cc"]) "color"))
          (is (nil? (get-in after ["agents" "cc" "color"])))
          (is (str/includes? (json/generate-string (:diffs (op))) "\"color\":null"))))
      (testing "an unchanged null is no change"
        (u/write-text! target (str/replace (get-in c [:statusline :text]) "\"full\"" "\"compact\""))
        (let [out (render)]
          (is (str/includes? out "context_style: \"compact\" -> \"full\""))
          (is (not (str/includes? out "∅"))))))
    (fs/delete-if-exists target)))

(deftest a-run-that-cannot-write-the-file-is-not-stopped-by-it
  (let [c (cfg {:bin stub :defaults {:segments [:model :model]}})
        level #(:level (first (filter (fn [f] (= [:statusline :config] (:where f))) (sl/findings c %))))]
    (is (= :error (level {})))
    (is (= :error (level {:kinds #{:statusline}})))
    (is (= :error (level {:kinds #{:statusline} :tools #{:claude}})))
    (is (= :warn (level {:tools #{:claude}})))
    (is (= :warn (level {:kinds #{:mcps}})))
    (is (= :warn (level {:projects #{:x}}))))
  (testing "-k statusline plans it whatever -t says; a project scope never does"
    (let [c (cfg {:bin stub :defaults {:color :never}})]
      (is (seq (sl/plan-ops c state/empty-state {:kinds #{:statusline} :tools #{:claude}})))
      (is (empty? (sl/plan-ops c state/empty-state {:projects #{:x}}))))))

(deftest namespaces-are-kept-not-dropped
  (is (= "x/auto" (get-in (:data (sl/normalize {:defaults {:color :x/auto}})) ["defaults" "color"])))
  (is (= {"x/color" "auto"} (get (:data (sl/normalize {:defaults {:x/color :auto}})) "defaults"))))

(deftest import-reads-the-file-back
  (let [target (sl/config-path)
        decl {:defaults {:segments [:model :context] :context-style :full :warning-percent 40}
              :agents {:pi {:show-pacing false} :cc {:color nil}}}
        text (:text (sl/normalize decl))]
    (u/write-text! target text)
    (testing "the declaration it implies writes the same bytes"
      (is (= text (:text (sl/normalize (sl/scan))))))
    (testing "so --replace keeps it, and the next apply! has nothing to delete"
      (is (= text (:text (sl/normalize (:statusline (:config (imports/scan {:existing nil}))))))))
    (fs/delete-if-exists target)
    (is (nil? (sl/scan)))))

(deftest a-claude-status-line-names-the-renderer
  (let [c (config/parse-config
           (pr-str {:executors {:claude {:status-line {:type "command"
                                                       :command "\"~/tools/agent-statusline\" --agent cc"}}}
                    :statusline {:defaults {}}})
           (str u/home "/agents.edn"))]
    (is (= (str u/home "/tools/agent-statusline") (#'sl/claude-renderer c))))
  (is (nil? (#'sl/claude-renderer
             (config/parse-config (pr-str {:executors {:claude {:status-line {:type "command" :command "my-line"}}}})
                                  (str u/home "/agents.edn"))))))

(deftest t-leaves-it-out-and-ownership-records-the-path
  (let [c (cfg {:bin stub :defaults {:color :never}})]
    (is (seq (sl/plan-ops c state/empty-state {})))
    (is (empty? (sl/plan-ops c state/empty-state {:tools #{:claude}})))
    (is (empty? (sl/plan-ops c state/empty-state {:host :box})))
    (let [[[t k id data]] (sl/inventory c)]
      (is (= [:agentctl :statusline :config] [t k id]))
      (is (= (sl/config-path) (:path data)))
      (is (= (u/sha256 (get-in c [:statusline :text])) (:sha256 data))))
    (testing "the whole-config inventory carries it, whatever the executors"
      (is (some #(= [:agentctl :statusline :config] (vec (take 3 %))) (core/inventory c))))))

(deftest selectors-name-it-by-kind-and-only-reaches-it-by-name
  (let [c (cfg {:bin stub :defaults {:color :never}})]
    (is (some #{:statusline} core/kinds) "-k statusline and --only statusline/config are accepted")
    (is (seq (sl/plan-ops c state/empty-state {:only [[:statusline "config"]]})))
    (is (= [:statusline]
           (distinct (map :kind (core/build-plan c state/empty-state {:only [[:statusline "config"]]})))))
    (is (empty? (sl/plan-ops c state/empty-state {:only [[:skills "review"]]}))
        "a run narrowed to other entities never touches the file")
    (is (not (sl/selected? {:only [[:mcps "x"]]})))
    (is (sl/selected? {:user true}) "user scope is where the file lives")
    (is (empty? (core/unmatched-only c state/empty-state {:only [[:statusline "config"]]}))
        "its id names a file, not a declaration")))

(defn- write-bytes [path & parts]
  (with-open [o (java.io.FileOutputStream. (str path))]
    (doseq [p parts] (.write o (if (string? p) (.getBytes ^String p "ISO-8859-1") (byte-array p)))))
  path)

(deftest awl-is-read-for-the-renderer-it-links-never-run
  (let [dir (str u/home "/awl-builds")]
    (fs/create-dirs dir)
    (testing "the renderer's config loader is linked in: a -Dstatusline build"
      (is (= :statusline (sl/awl-build (write-bytes (str dir "/elf") [0x7f 0x45 0x4c 0x46 0 0]
                                                    "...AGENT_STATUSLINE_CONFIG...")))))
    (testing "a native binary without it keeps its own row"
      (is (= :plain (sl/awl-build (write-bytes (str dir "/macho") [0xcf 0xfa 0xed 0xfe 0 0] "awl")))))
    (testing "a wrapper script cannot be read this way, whatever it mentions"
      (is (= :opaque (sl/awl-build (write-bytes (str dir "/script")
                                                "#!/bin/sh\n# AGENT_STATUSLINE_CONFIG\nexec awl\n")))))))

(deftest only-an-awl-override-asks-about-awl
  (is (:awl? (sl/normalize {:agents {:awl {:color :never}}})))
  (is (not (:awl? (sl/normalize {:agents {:pi {:color :never}}}))))
  (is (not (:awl? (sl/normalize {:defaults {:color :never}})))))

(deftest a-statusline-survives-import
  ;; dropped here, the next apply! would read the declaration as removed and
  ;; delete the file agentctl owns
  (let [decl {:defaults {:segments [:model]} :agents {:pi {:color :never}}}]
    (doseq [existing [{:statusline decl}
                      {:mcps [{:id :x :cmd "/bin/echo" :in :all}] :statusline decl}]]
      (let [{:keys [config]} (imports/scan {:existing existing})]
        (is (= decl (:statusline config)))
        (is (= decl (:statusline (clojure.edn/read-string (imports/render config)))))))))

(let [{:keys [fail error]} (run-tests 'statusline-test)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
