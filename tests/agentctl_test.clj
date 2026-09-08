(ns agentctl-test
  (:require [agentctl.adapters.antigravity :as antigravity]
            [agentctl.adapters.claude :as claude]
            [agentctl.adapters.codex :as codex]
            [agentctl.adapters.common :as common]
            [agentctl.adapters.llm :as llm]
            [agentctl.adapters.pi :as pi]
            [agentctl.config :as config]
            [agentctl.core :as core]
            [agentctl.edit :as edit]
            [agentctl.form :as form]
            [agentctl.gui :as gui]
            [agentctl.imports :as imports]
            [agentctl.plan :as plan]
            [agentctl.refs :as refs]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.toml :as toml]
            [agentctl.util :as u]
            [agentctl.validate :as validate]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

(defn temp-dir [] (str (fs/create-temp-dir {:prefix "agentctl-test-"})))

;; ---------------------------------------------------------------- toml

(def sample-toml
  (str "# lead comment\n"
       "model = \"a\"\n"
       "personality = \"x\"\n"
       "\n"
       "[projects.\"/Users/example/x\"]\n"
       "trust_level = \"trusted\"\n"
       "\n"
       "[hooks.state]\n"
       "opaque = \"do-not-touch\"\n"))

(deftest toml-surgery-preserves-everything-else
  (testing "setting a root key rewrites only that line"
    (let [out (toml/set-key sample-toml [] :model "b")]
      (is (str/includes? out "model = \"b\""))
      (is (str/includes? out "# lead comment"))
      (is (str/includes? out "opaque = \"do-not-touch\""))
      (is (str/includes? out "[projects.\"/Users/example/x\"]"))))

  (testing "a new table is appended, quoted where required"
    (let [out (toml/set-key sample-toml ["projects" "/Users/example/y"] :trust_level "trusted")]
      (is (str/includes? out "[projects.\"/Users/example/y\"]"))
      (is (str/includes? out "trust_level = \"trusted\""))))

  (testing "removing a table takes its sub-tables with it"
    (let [out (toml/remove-table sample-toml ["hooks"])]
      (is (not (str/includes? out "opaque")))
      (is (str/includes? out "model = \"a\""))))

  (testing "values encode by type"
    (is (= "true" (toml/encode-value true)))
    (is (= "[\"a\", \"b\"]" (toml/encode-value ["a" "b"])))
    (is (= "\"say \\\"hi\\\"\"" (toml/encode-value "say \"hi\"")))))

(deftest toml-reads-back-what-it-writes
  (let [dir (temp-dir)
        f (str dir "/config.toml")]
    (u/write-text! f sample-toml)
    (toml/update-file! f #(toml/set-key % ["mcp_servers" "demo"] :command "/bin/echo"))
    (let [parsed (toml/read-toml f)]
      (is (= "/bin/echo" (get-in parsed ["mcp_servers" "demo" "command"])))
      (is (= "trusted" (get-in parsed ["projects" "/Users/example/x" "trust_level"]))))))

;; ---------------------------------------------------------------- refs

(deftest ref-parsing
  (is (= :env (:ref/kind (refs/parse (symbol "$FOO")))))
  (is (= "FOO" (:ref/var (refs/parse "!env://FOO"))))
  (is (= :bw (:ref/kind (refs/parse "!bw://folder/item/field"))))
  (is (= ["folder" "item" "field"]
         ((juxt :ref/folder :ref/item :ref/field) (refs/parse "!bw://folder/item/field"))))
  (is (= ["item" "field"]
         ((juxt :ref/item :ref/field) (refs/parse "!bw://item/field"))))
  (is (= :literal (:ref/kind (refs/parse "plain")))))

(deftest describe-never-leaks-a-secret
  (let [r (refs/parse "!bw://dev/item/field")]
    (is (= "bw:dev/item/field" (refs/describe r)))
    (is (not (str/includes? (refs/describe r) "password")))))

(deftest secret-shape-detection
  (is (refs/secret-shaped? :api_key "whatever"))
  (is (refs/secret-shaped? :harmless (str "sk" "-0123456789abcdef")))
  (is (refs/secret-shaped? :x "xoxc-1-2-3"))
  (is (not (refs/secret-shaped? :model "sonnet"))))

(deftest env-refs-resolve
  (let [r (refs/parse "!env://PATH")]
    (is (= :ok (:status (refs/resolve-ref r)))))
  (is (= :missing (:status (refs/resolve-ref (refs/parse "!env://AGENTCTL_DEFINITELY_UNSET"))))))

;; ---------------------------------------------------------------- config

(def sample-config
  '{:executors {:claude {:model "sonnet"}}
    :mcps {:good {:command "/bin/echo" :tools [:pi]}
           :bad {}}
    :skill-packs {:local {:uri "file:///tmp"}}
    :skills {:demo {:from :local}
             :orphan {:from :nope}}
    :extra-providers {:p {:url "http://x" :key $SOME_VAR}}
    :projects {:proj {:path "/tmp/proj" :trusted true :mcp [:good :missing]}}})

(deftest def-bindings-substitute-at-root
  (let [{:keys [raw findings]}
        (config/expand-defs '{:#def {effort "high" who :claude}
                              :executors {:claude {:thinking $effort :model $who}}
                              :extra-providers {:omni {:key $PROVIDER_API_KEY}}})]
    (is (empty? findings))
    (is (= {:thinking "high" :model :claude} (get-in raw [:executors :claude])))
    (testing "an unbound SHOUTED $NAME stays an env reference"
      (is (= :env (:ref/kind (refs/parse (get-in raw [:extra-providers :omni :key]))))))
    (testing ":#def itself is consumed"
      (is (not (contains? raw :#def))))))

(deftest def-bindings-interpolate-into-strings
  (let [{:keys [raw]} (config/expand-defs '{:#def {ws "~/projects"}
                                            :mcps {:x {:cmd "bun run $ws/a/index.ts"}}})]
    (is (= "bun run ~/projects/a/index.ts" (get-in raw [:mcps :x :cmd])))
    (testing "an unbound $NAME in a string is left for env expansion"
      (is (= "$HOME/y" (:cmd (:y (:mcps (:raw (config/expand-defs
                                                '{:#def {ws "~/projects"}
                                                  :mcps {:y {:cmd "$HOME/y"}}}))))))))))

(deftest let-is-root-only
  (let [{:keys [findings]} (config/expand-defs '{:#def {a "1"}
                                                 :projects {:p {:#def {b "2"} :trusted true}}})]
    (is (= [:error] (map :level findings)))
    (is (str/includes? (:message (first findings)) "top level")))
  (testing "a binding referencing another binding is an error, not an ordering puzzle"
    (is (= [:error] (map :level (:findings (config/expand-defs '{:#def {a "1" b $a}})))))))

(deftest unbound-lowercase-ref-warns
  (let [{:keys [findings]} (config/expand-defs '{:#def {effort "high"}
                                                 :executors {:claude {:thinking $efort}}})]
    (is (= [:warn] (map :level findings)))
    (is (str/includes? (:message (first findings)) "$efort"))))

(deftest on-off-sets-expand-to-booleans
  (let [cfg (config/normalize '{:executors {:claude {:on #{:ultracode :auto-compact}
                                                    :off #{:telemetry}
                                                    :model "sonnet"}}} "x")
        s (get-in cfg [:tools :claude])]
    (is (= {:ultracode true :auto-compact true :telemetry false :model "sonnet"} s))
    (testing ":on is sugar, never a setting of its own"
      (is (not (contains? s :on))))))

(deftest permissions-mini-dsl-compiles
  (let [perms #(get-in (config/normalize {:projects {:p {:permissions %}}} "x")
                       [:projects :p :permissions])]
    (is (= {:allow ["Bash(bb:*)" "Bash(clj:*)"]}
           (perms {:allow {:Bash ["bb:*" "clj:*"]}})))
    (is (= {:allow ["Bash"]} (perms {:allow {:Bash :all}})))
    (is (= {:allow ["mcp__clj-repl__list_repls"] :ask ["mcp__clj-repl__eval"]}
           (perms '{:allow {:Mcp {:clj-repl #{list_repls}}}
                    :ask {:Mcp {:clj-repl #{eval}}}})))
    (is (= {:deny ["mcp__slack"]} (perms {:deny {:Mcp {:slack :all}}})))
    (testing "the flat form already used in examples/agents.edn passes through"
      (is (= {:allow ["Bash(git status:*)"]} (perms {:allow ["Bash(git status:*)"]}))))
    (testing "keys agentctl does not model are preserved"
      (is (= {:defaultMode "acceptEdits" :allow ["Bash"]}
             (perms {:defaultMode "acceptEdits" :allow {:Bash :all}}))))))

(deftest cmd-string-splits-into-command-and-args
  (let [m (get-in (config/normalize {:mcps {:x {:cmd "/bin/srv --transport stdio 'a b'"
                                                :type :stdio
                                                :args ["--last"]}}} "x")
                  [:mcps :x])]
    (is (= "/bin/srv" (:command m)))
    (is (= ["--transport" "stdio" "a b" "--last"] (:args m)))
    (is (= :stdio (:transport m)))))

(deftest project-parent-locates-the-directory
  (let [ps (:projects (config/normalize '{:projects {:a {:parent "~/work"}
                                                     :b {:parent "~/work" :path "/srv/b"}
                                                     :c {}}} "x"))]
    (is (= (str u/home "/work/a") (:path (:a ps))))
    (testing ":path is the whole location and wins over :parent"
      (is (= "/srv/b" (:path (:b ps)))))
    (testing "neither given falls back to ~/projects/<id>"
      (is (= (str u/home "/projects/c") (:path (:c ps)))))))

(deftest project-named-mcps-are-project-scoped
  (let [cfg (config/normalize {:mcps {:slack "/bin/slack-mcp"
                                      :searxng "/bin/searxng-mcp"
                                      :everywhere {:cmd "/bin/x" :scope :global}}
                               :projects {:example {:path "/tmp/on"
                                                      :mcp [:slack :everywhere]}}}
                              "x")
        mcps (:mcps cfg)]
    (testing "a project's own server defaults to local scope — its entry in ~/.claude.json"
      (is (= :local (get-in mcps [:slack :scope]))))
    (is (= #{:example} (get-in mcps [:slack :used-by])))
    (testing "an MCP no project names stays machine-wide"
      (is (= :global (get-in mcps [:searxng :scope]))))
    (testing ":scope :global wins over the inference"
      (is (= :global (get-in mcps [:everywhere :scope]))))
    (testing "only global servers reach the user-wide installers"
      (is (= #{:searxng :everywhere}
             (set (keys (common/global-mcps mcps :claude))))))
    (testing "tools without project MCP config report the skip instead of dropping it"
      (let [ops (common/project-scope-skip-ops cfg :codex)]
        (is (= 1 (count ops)))
        (is (true? (:warn (first ops))))
        (is (str/includes? (:summary (first ops)) "slack"))))))

(deftest only-tools-a-project-names-are-planned-for
  (let [cfg (config/normalize
             {:executors {:claude {:model "sonnet"} :codex {:model "gpt"} :pi {}}
              :extra-providers {:prov {:url "http://x" :models ["m"]}}
              :projects {:example {:path "/tmp/on" :executors #{:claude}}}}
             "x")]
    (is (= #{:claude} (config/active-tools cfg)))
    (testing "an executor nothing names is reported, not silently ignored"
      (let [warns (->> (config/structural-findings cfg)
                       (filter #(= :warn (:level %)))
                       (map (comp vec :where)))]
        (is (some #{[:executors :codex]} warns))
        (is (some #{[:executors :pi]} warns))
        (is (not (some #{[:executors :claude]} warns)))))
    (testing "providers are not written for a tool no project asked for"
      (is (empty? (filter #(= :codex (:tool %)) (core/build-plan cfg state/empty-state {}))))))
  (testing "a config whose projects name no executors keeps every tool"
    (let [cfg (config/normalize {:executors {:pi {}} :projects {:p {:path "/tmp/p"}}} "x")]
      (is (= (set config/all-tools) (config/active-tools cfg)))))
  (testing "a config with no projects at all keeps every tool"
    (let [cfg (config/normalize {:executors {:pi {}}} "x")]
      (is (= (set config/all-tools) (config/active-tools cfg))))))

(deftest a-projects-executors-narrow-its-skills
  (let [cfg (config/normalize
             {:skills {:wrap-up {:path "/tmp/skills/wrap-up"}
                       :notes {:path "/tmp/skills/notes"}
                       :everywhere {:path "/tmp/skills/everywhere" :tools :all}}
              :projects {:example {:path "/tmp/on"
                                     :executors #{:claude}
                                     :skills [:wrap-up :everywhere]}}}
             "x")
        skills (:skills cfg)]
    (is (= #{:claude} (:tools (:wrap-up skills))))
    (testing "a skill no project names keeps every tool that can hold one"
      (is (= (set (config/tools-for :skills)) (:tools (:notes skills)))))
    (testing "an explicit :tools on the skill wins over the project"
      (is (= (set (config/tools-for :skills)) (:tools (:everywhere skills)))))
    (testing "a project that names no executors narrows nothing"
      (let [open (config/normalize
                  {:skills {:wrap-up {:path "/tmp/skills/wrap-up"}}
                   :projects {:other {:path "/tmp/other" :skills [:wrap-up]}}}
                  "x")]
        (is (= (set (config/tools-for :skills))
               (:tools (:wrap-up (:skills open)))))))))

(deftest mcp-plan-lines-name-the-server-not-its-shape
  (let [{:keys [searxng remote]}
        (:mcps (config/normalize {:mcps {:searxng "/opt/homebrew/bin/searxng-mcp --stdio"
                                         :remote "https://example.com/mcp"}} "x"))]
    (is (= "/opt/homebrew/bin/searxng-mcp --stdio" (common/mcp-summary searxng)))
    (is (= "https://example.com/mcp  (http)" (common/mcp-summary remote)))
    (testing "a long command line is elided, not wrapped"
      (is (>= 96 (count (common/mcp-summary
                         (assoc searxng :args (repeat 40 "--flag")))))))))

(deftest bare-string-mcp-is-a-command
  (let [mcps (:mcps (config/normalize {:mcps {:searxng "/opt/homebrew/bin/searxng-mcp --stdio"
                                              :remote "https://example.com/mcp"}} "x"))]
    (is (= {:command "/opt/homebrew/bin/searxng-mcp" :args ["--stdio"] :transport :stdio}
           (select-keys (:searxng mcps) [:command :args :transport])))
    (testing "a bare URL is the http spelling, not a binary named https://"
      (is (= {:url "https://example.com/mcp" :transport :http}
             (select-keys (:remote mcps) [:url :transport]))))
    (testing "the shorthand survives the structural check"
      (is (empty? (filter #(= :error (:level %))
                          (config/structural-findings
                           (config/normalize {:mcps {:searxng "/bin/x"}} "x"))))))))

(deftest permission-lists-compare-order-insensitively
  (let [dir (temp-dir) f (str dir "/settings.json")]
    (u/write-json! f {:permissions {:allow ["Bash(b)" "Bash(a)"]}})
    (is (nil? (plan/json-set-op {:tool :claude :kind :projects :id :p :file f
                                 :path [:permissions :allow] :value ["Bash(a)" "Bash(b)"]
                                 :compare-as :set})))
    (is (some? (plan/json-set-op {:tool :claude :kind :projects :id :p :file f
                                  :path [:permissions :allow] :value ["Bash(a)"]
                                  :compare-as :set})))))

(deftest cli-code-is-still-read-under-its-old-name
  (let [cfg (config/normalize {:cli-code {:claude {:model "sonnet"}}} "x")]
    (is (= {:model "sonnet"} (get-in cfg [:tools :claude])))
    (is (= [:warn] (map :level (filter #(= [:cli-code] (:where %))
                                       (config/structural-findings cfg)))))))

(deftest normalization
  (let [cfg (config/normalize sample-config "/tmp/agents.edn")]
    (testing "tool selection defaults to every capable tool"
      (is (= #{:pi} (get-in cfg [:mcps :good :tools])))
      (is (= (set (config/tools-for :providers)) (get-in cfg [:providers :p :tools]))))
    (testing "unquoted $SYMBOL becomes an env ref"
      (is (= :env (get-in cfg [:providers :p :key :ref/kind])))
      (is (= "SOME_VAR" (get-in cfg [:providers :p :key :ref/var]))))
    (testing "project paths are absolute"
      (is (= "/tmp/proj" (get-in cfg [:projects :proj :path]))))))

(deftest one-provider-entry-serves-two-dialects-and-two-pools
  (let [cfg (config/normalize
             {:extra-providers
              {:router {:url "https://switch.yard/"
                        :api ["anthropic-messages" "openai-completions"]
                        :headers {"x-router-tenant" "acme"}
                        :models [{:id "big" :headers {"x-router-pool" "a"}}
                                 {:id "small" :headers {"x-router-pool" "b"}}]}}}
             "/tmp/agents.edn")
        p (get-in cfg [:providers :router])]
    (testing "a bare id and a map form normalize alike"
      (is (= ["big" "small"] (mapv :id (:models p))))
      (is (= "a" (get-in p [:models 0 :headers "x-router-pool" :ref/value]))))
    (testing "each tool takes the dialect it speaks, and the path root that goes with it"
      (is (= {:api "anthropic-messages" :url "https://switch.yard"}
             (common/provider-dialect p pi/dialects)))
      (is (= {:api "openai-completions" :url "https://switch.yard/v1"}
             (common/provider-dialect p llm/dialects))))
    (testing "a scalar :api is still passed through as-is, path and all"
      (let [one (get-in (config/normalize
                         {:extra-providers {:o {:url "https://o.example/v1"}}}
                         "/tmp/agents.edn")
                        [:providers :o])]
        (is (= {:api "openai-completions" :url "https://o.example/v1"}
               (common/provider-dialect one llm/dialects)))))
    (testing "a provider offering nothing the tool speaks is reported, not skipped"
      (let [only-anthropic (assoc p :api ["anthropic-messages"])]
        (is (nil? (common/provider-dialect only-anthropic llm/dialects)))
        (is (true? (:warn (common/no-dialect-op :llm :router only-anthropic))))))))

(deftest structural-checks-catch-dangling-references
  (let [cfg (config/normalize sample-config "/tmp/agents.edn")
        msgs (map :message (config/structural-findings cfg))]
    (is (some #(str/includes? % "undefined pack") msgs))
    (is (some #(str/includes? % "stdio MCP server needs :command") msgs))
    (is (some #(str/includes? % "undefined mcp :missing") msgs))))

(deftest capability-table
  (is (config/supports? :codex :mcps))
  (is (not (config/supports? :unknown :mcps)))
  (is (not (config/supports? :llm :mcps)))
  (is (config/supports? :antigravity :skills))
  (is (not (config/supports? :antigravity :providers)))
  (is (= "agy" (config/cli-name :antigravity)))
  (is (= "codex" (config/cli-name :codex)))
  (is (= [:claude :codex :pi :omp :antigravity] (config/tools-for :skills))))

(deftest antigravity-speaks-its-own-mcp-dialect
  (let [entry #'antigravity/mcp-entry
        {:keys [local remote off]}
        (:mcps (config/normalize {:mcps {:local "/bin/echo hi"
                                         :remote {:url "https://example.com/sse"
                                                  :headers {"Authorization" "Bearer y"}}
                                         :off {:cmd "/bin/echo" :enabled false}}}
                                 "x"))]
    (testing "stdio servers carry command/args and an explicit disabled flag"
      (is (= {:command "/bin/echo" :args ["hi"] :disabled false} (entry local nil))))
    (testing "the endpoint is serverUrl, never url"
      (let [e (entry remote nil)]
        (is (= "https://example.com/sse" (:serverUrl e)))
        (is (nil? (:url e)))
        (is (= {"Authorization" "Bearer y"} (:headers e)))))
    (testing ":enabled false is written as disabled true, not as enabled false"
      (let [e (entry off nil)]
        (is (true? (:disabled e)))
        (is (not (contains? e :enabled)))))
    (testing "keys antigravity already stores survive a rewrite"
      (is (= "keep" (:someNativeKey (entry local {:someNativeKey "keep"})))))))

(deftest an-authorization-header-is-masked-in-the-plan
  ;; the value is the whole credential while the key name says nothing about it
  (is (refs/credential? "Authorization" "Bearer sk-live-abcdefgh")))

;; ---------------------------------------------------------------- codex keymap

(deftest codex-keymap-preserves-unmanaged-settings-and-converges
  (let [f (str (temp-dir) "/config.toml")
        original (str "# keep this comment\nmodel = \"old\"\n"
                      "[tui]\nanimations = false\n"
                      "[tui.keymap.global]\nopen_transcript = \"ctrl-t\"\ncopy = \"alt-c\"\n"
                      "[hooks.state]\nlast_run = 42\n")
        cfg (config/normalize
             {:executors {:codex {:model "new"
                                   :keymap {:global {:open_transcript ["ctrl-t" "alt-t"]}
                                            "composer" {"submit" "ctrl-enter" :queue []}}}}} nil)]
    (u/write-text! f original)
    (with-redefs [codex/config-file f]
      (let [ops (vec (codex/settings-ops cfg))]
        (is (= original (slurp f)) "planning does not write")
        (is (= #{:global :keymap/global :keymap/composer} (set (map :id ops))))
        (is (not-any? :warn ops))
        (doseq [op ops] ((:exec! op))))
      (let [t (toml/read-toml f)]
        (is (= "new" (get t "model")))
        (is (= {"open_transcript" ["ctrl-t" "alt-t"] "copy" "alt-c"}
               (get-in t ["tui" "keymap" "global"])))
        (is (= {"submit" "ctrl-enter" "queue" []}
               (get-in t ["tui" "keymap" "composer"])))
        (is (false? (get-in t ["tui" "animations"])))
        (is (= 42 (get-in t ["hooks" "state" "last_run"])))
        (is (str/includes? (slurp f) "# keep this comment"))
        (is (empty? (codex/settings-ops cfg)) "second plan has no drift")
        (is (= (get-in t ["tui" "keymap"])
               (get-in (imports/scan-settings) [:codex :keymap])))
        (is (empty? (codex/settings-ops
                     (config/normalize {:executors (select-keys (imports/scan-settings) [:codex])} nil)))
            "imported bindings round-trip without drift"))
      (is (empty? (codex/settings-ops
                   (config/normalize {:executors {:codex {:keymap {}}}} nil))))
      (is (empty? (codex/settings-ops (config/normalize {} nil)))))))

(deftest codex-keymap-creates-a-missing-config-and-rejects-malformed-bindings
  (let [f (str (temp-dir) "/config.toml")]
    (with-redefs [codex/config-file f]
      (doseq [keymap [nil "ctrl-t" {:global "ctrl-t"}
                      {:global {:copy false}} {:global {:copy ["ctrl-c" 42]}}]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"codex :keymap"
                             (doall (codex/settings-ops
                                     (config/normalize {:executors {:codex {:keymap keymap}}} nil))))))
      (is (not (u/exists? f)))
      (let [cfg (config/normalize {:executors {:codex {:keymap {:global {:copy []}}}}} nil)]
        (doseq [op (codex/settings-ops cfg)] ((:exec! op)))
        (is (= [] (get-in (toml/read-toml f) ["tui" "keymap" "global" "copy"])))
        (is (empty? (codex/settings-ops cfg)))))))

(deftest codex-keymap-is-editable-as-edn
  (let [keymap {:global {:open_transcript "alt-t"}}
        m (form/model (pr-str {:executors {:codex {:keymap keymap}}}))
        section (first (filter #(= ":executors" (:key %)) (:sections m)))
        entry (first (filter #(= ":codex" (:id %)) (:entries section)))
        field (first (filter #(= "keymap" (:key %)) (:fields entry)))]
    (is (= "edn" (:type field)))
    (is (= keymap (form/coerce :edn (:value field))))))

;; ---------------------------------------------------------------- settings schema

(def validate-value
  "Private in agentctl.validate — a hand-rolled JSON Schema subset with no
   library to test against, so its keyword coverage needs direct assertions."
  #'validate/validate-value)

(deftest schema-type-checking
  (is (empty? (validate-value {} {:type "string"} "x" [])))
  (is (seq (validate-value {} {:type "string"} 1 [])))
  (is (empty? (validate-value {} {:type ["string" "null"]} nil [])) "union type"))

(deftest schema-enum-and-const
  (is (empty? (validate-value {} {:enum ["a" "b"]} "a" [])))
  (is (seq (validate-value {} {:enum ["a" "b"]} "c" [])))
  (is (empty? (validate-value {} {:const "x"} "x" [])))
  (is (seq (validate-value {} {:const "x"} "y" []))))

(deftest schema-pattern-and-min-length
  (is (empty? (validate-value {} {:pattern "^a.*"} "abc" [])))
  (is (seq (validate-value {} {:pattern "^a.*"} "bbc" [])))
  (is (seq (validate-value {} {:minLength 3} "ab" [])))
  (is (empty? (validate-value {} {:minLength 3} "abc" []))))

(deftest schema-required-and-additional-properties
  (let [schema {:properties {:a {:type "string"}} :required ["a"] :additionalProperties false}]
    (is (empty? (validate-value {} schema {:a "x"} [])))
    (is (seq (validate-value {} schema {} [])) "missing required")
    (is (seq (validate-value {} schema {:a "x" :b 1} [])) "extra property forbidden")))

(deftest schema-items
  (let [schema {:type "array" :items {:type "string"}}]
    (is (empty? (validate-value {} schema ["a" "b"] [])))
    (is (seq (validate-value {} schema ["a" 1] [])))))

(deftest schema-ref-resolves-one-level-into-defs
  (let [root {:$defs {:name {:type "string" :minLength 1}}}
        schema {:$ref "#/$defs/name"}]
    (is (empty? (validate-value root schema "art" [])))
    (is (seq (validate-value root schema "" [])))))

(deftest schema-any-of-passes-if-one-branch-matches
  (let [schema {:anyOf [{:type "string"} {:type "integer"}]}]
    (is (empty? (validate-value {} schema "x" [])))
    (is (empty? (validate-value {} schema 1 [])))
    (is (seq (validate-value {} schema true [])))))

(deftest schema-unknown-keyword-is-ignored-not-failed
  (is (empty? (validate-value {} {:format "uri" :minimum 5} "not-a-uri" []))
      "an upstream schema update must never turn into a false positive here"))

;; ---------------------------------------------------------------- plan

(deftest nested-map-diffs-render-field-by-field
  (let [o (plan/op {:action :update :tool :claude :kind :mcps :id :example/clj-repl
                    :summary "x"
                    :diffs [{:key :clj-repl
                             :before {:command "/Users/example/.bun/bin/bun" :args ["run" "i.ts"]}
                             :after {:command "bun" :args ["run" "i.ts"] :type "stdio"}}]})
        out (plan/render-op o {})]
    (testing "the changed field is named, not the whole map"
      (is (str/includes? out "command: \"/Users/example/.bun/bin/bun\" -> \"bun\"")))
    (testing "an added key reads as an addition"
      (is (str/includes? out "+ type: \"stdio\"")))
    (testing "unchanged fields stay out of the way"
      (is (not (str/includes? out "args"))))
    (testing "the redundant header keyed by the resource itself is dropped"
      (is (not (re-find #"(?m)^\s+[~+-] clj-repl$" out))))))

(deftest field-diffs-ignore-representation
  (is (empty? (plan/field-diffs {:a ["x"]} {:a '("x")})))
  (is (empty? (plan/field-diffs {:a :sonnet} {:a "sonnet"})))
  (is (= 1 (count (plan/field-diffs {:a 1} {:a 2})))))

(deftest json-set-op-is-a-noop-when-converged
  (let [dir (temp-dir)
        f (str dir "/settings.json")]
    (u/write-json! f {:model "sonnet"})
    (is (nil? (plan/json-set-op {:tool :claude :kind :settings :id :model
                                 :file f :path [:model] :value "sonnet"})))
    (let [op (plan/json-set-op {:tool :claude :kind :settings :id :model
                                :file f :path [:model] :value "opus"})]
      (is (= :update (:action op)))
      ((:exec! op))
      (is (= "opus" (:model (u/read-json f)))))))

(deftest concurrent-ops-on-one-file-do-not-clobber
  (testing "each exec re-reads, so two ops on the same file both survive"
    (let [dir (temp-dir)
          f (str dir "/settings.json")
          a (plan/json-set-op {:tool :pi :kind :settings :id :a :file f :path [:a] :value 1})
          b (plan/json-set-op {:tool :pi :kind :settings :id :b :file f :path [:b] :value 2})]
      ((:exec! a))
      ((:exec! b))
      (is (= {:a 1 :b 2} (u/read-json f))))))

(deftest link-op-never-replaces-a-resource-with-a-link-to-itself
  (testing "a skill that already lives in the tool's own directory is left alone"
    (let [dir (temp-dir)
          skill (str dir "/skills/demo")]
      (fs/create-dirs skill)
      (u/write-text! (str skill "/SKILL.md") "---\nname: demo\ndescription: d\n---\n")
      (is (nil? (plan/link-op {:tool :pi :kind :skills :id :demo :src skill :dest skill})))
      (is (fs/directory? skill)))))

(deftest link-op-relinks-and-is-idempotent
  (let [dir (temp-dir)
        src (str dir "/src")
        dest (str dir "/dest")]
    (fs/create-dirs src)
    (let [op (plan/link-op {:tool :pi :kind :skills :id :demo :src src :dest dest})]
      (is (= :create (:action op)))
      ((:exec! op)))
    (is (fs/sym-link? dest))
    (is (nil? (plan/link-op {:tool :pi :kind :skills :id :demo :src src :dest dest})))))

;; ---------------------------------------------------------------- state

(deftest backup-keeps-the-pre-run-state
  (testing "a second op on the same file must not overwrite the first backup"
    (let [dir (temp-dir)
          f (str dir "/settings.json")]
      (binding [u/*backup-root* (str dir "/backups")]
        (u/write-json! f {:v "before"})
        (let [b (u/backup! f)]
          (u/write-json! f {:v "after"})
          (is (= b (u/backup! f)))
          (is (= "before" (:v (u/read-json b)))))))))

(deftest state-tracks-ownership
  (let [st (-> state/empty-state
               (state/record :pi :mcps :demo {:target "x"})
               (state/record :pi :skills :other {}))]
    (is (state/managed? st :pi :mcps :demo))
    (is (= #{"demo"} (state/managed-ids st :pi :mcps)))
    (is (not (state/managed? (state/forget st :pi :mcps :demo) :pi :mcps :demo)))))

;; ---------------------------------------------------------------- sources

(deftest skill-source-resolution
  (let [dir (temp-dir)
        pack (str dir "/pack")
        skill (str pack "/skills/demo")]
    (fs/create-dirs skill)
    (u/write-text! (str skill "/SKILL.md") "---\nname: demo\ndescription: d\n---\n")
    (let [cfg (config/normalize {:skill-packs {:p {:uri (str "file://" pack)}}
                                 :skills {:demo {:from :p}}}
                                "/tmp/agents.edn")]
      (is (= skill (sources/skill-source cfg (get-in cfg [:skills :demo]))))
      (is (= [skill] (sources/skill-dirs (get-in cfg [:skill-packs :p])))))))

;; ---------------------------------------------------------------- imports

(deftest import-redacts-secrets
  (let [rendered (imports/render {:extra-providers {:x {:url "http://x" :key "!bw://provider-x/apiKey"}}})]
    (is (str/includes? rendered "!bw://provider-x/apiKey"))
    (is (str/includes? rendered ";; agents.edn"))))

(deftest rendered-config-is-readable-edn
  (let [cfg {:executors {:claude {:model "sonnet"}}
             :mcps {:demo {:command "/bin/echo" :tools [:pi]}}}
        back (clojure.edn/read-string (imports/render cfg))]
    (is (= cfg back))))


(deftest project-scoped-mcps-are-owned-under-the-project
  (let [cfg (config/normalize {:mcps {:slack "/bin/slack-mcp"
                                      :searxng {:cmd "/bin/searxng-mcp" :scope :global}}
                               :projects {:example {:path "/tmp/on" :mcp [:slack]}}}
                              "x")
        inv (set (core/inventory cfg))]
    (testing "a project's server is owned per project, never as the user-wide one"
      (is (contains? inv [:claude :mcps :example/slack]))
      (is (not (contains? inv [:claude :mcps :slack]))))
    (testing "tools with no project MCP config own nothing for it"
      (is (not-any? #(= [:mcps :example/slack] (rest %))
                    (filter #(not= :claude (first %)) inv))))
    (testing "global servers stay plain"
      (is (contains? inv [:claude :mcps :searxng])))
    (testing "the manifest key keeps the namespace apart from the bare name"
      (is (not= (state/key-for :claude :mcps :example/slack)
                (state/key-for :claude :mcps :slack))))))

(deftest a-project-server-never-uninstalls-its-user-wide-namesake
  ;; the prune loop reads ids out of the manifest: if `:example/slack` were
  ;; read back as `slack` it would uninstall a user-wide server we do not own
  (let [st (state/record state/empty-state :claude :mcps :example/slack {})]
    (is (= #{"example/slack"} (state/managed-ids st :claude :mcps)))
    (is (some? (namespace (keyword (first (state/managed-ids st :claude :mcps))))))))

(deftest a-plaintext-token-warns-like-a-resolved-one
  ;; nothing resolves a literal, so this warning is the only one it ever gets —
  ;; and .mcp.json inside a project is normally committed
  (is (common/holds-secret? {"NEW_RELIC_API_KEY" "NRAK-abc123"}))
  (is (common/holds-secret? {"TOKEN" "!bw://example-vault/x/y"}))
  (is (not (common/holds-secret? {"SLACK_MCP_CHANNELS_CACHE" "/tmp/channels.json"}))))

(defn- mcp-op [id action diffs]
  (plan/op {:action action :tool :claude :kind :mcps :project :example
            :id (keyword (str "example/" id))
            :target (str u/home "/projects/example/.mcp.json")
            :summary (str id " summary")
            :diffs diffs}))

(deftest ops-on-one-file-render-as-one-block
  ;; five servers landing in one .mcp.json is one edit, and reads like one
  (let [ops [(mcp-op "clj-repl" :update [{:key :clj-repl
                                          :before {:command "/Users/example/.bun/bin/bun"}
                                          :after {:command "bun" :type "stdio"}}])
             (mcp-op "dbx" :update [{:key :dbx :before {:command "node"}
                                     :after {:command "node" :type "stdio"}}])
             (mcp-op "slack" :create [{:key :slack :before nil
                                       :after {:command "slack-mcp-server" :type "stdio"}}])]
        out (plan/render-plan ops {})]
    (testing "one header names every id under its shared namespace"
      (is (str/includes? out "projects/example mcps/{clj-repl dbx slack}"))
      (is (not (str/includes? out "mcps/clj-repl\n"))))
    (testing "the file is named once, relative to the project the header carries"
      (is (str/includes? out "edit `.mcp.json`:")))
    (testing "fields keep the name of the server they belong to"
      (is (str/includes? out "clj-repl.command:"))
      (is (str/includes? out "+ slack.type: \"stdio\"")))
    (testing "a mixed group is an edit of the file, not a create"
      (is (str/includes? out "~ projects/example mcps"))
      (is (not (str/includes? out "+ projects/example mcps"))))))

(deftest a-lone-op-is-untouched-by-grouping
  (let [out (plan/render-plan [(mcp-op "slack" :create [{:key :slack :before nil
                                                         :after {:command "x"}}])] {})]
    (is (str/includes? out "projects/example mcps/slack"))
    (is (not (str/includes? out "{slack}")))))

(deftest a-noop-never-joins-someone-elses-block
  ;; a warning noop is a message about a resource, not an edit to the file
  (let [ops [(mcp-op "slack" :create [{:key :slack :before nil :after {:command "x"}}])
             (mcp-op "miro" :create [{:key :miro :before nil :after {:command "y"}}])
             (plan/op {:action :noop :warn true :tool :claude :kind :mcps :project :example
                       :id :example/dead :target (str u/home "/projects/example/.mcp.json")
                       :summary "unsupported"})]
        out (plan/render-plan ops {:show-noop true})]
    (is (str/includes? out "= projects/example mcps/dead"))
    (is (str/includes? out "projects/example mcps/{miro slack}"))))

(deftest a-removal-inside-a-block-still-says-it-is-a-removal
  ;; a prune carries a summary and no diffs; folded into a block it must not
  ;; shrink to a name in the header list
  (let [ops [(mcp-op "slack" :create [{:key :slack :before nil :after {:command "x"}}])
             (plan/op {:action :delete :tool :claude :kind :mcps :project :example
                       :id :example/gone :target (str u/home "/projects/example/.mcp.json")
                       :summary "removed from agents.edn"})]
        out (plan/render-plan ops {})]
    (is (str/includes? out "projects/example mcps/{gone slack}"))
    (is (str/includes? out "- gone: removed from agents.edn"))))

(deftest a-project-scoped-resource-says-which-project
  (let [out (plan/render-op (plan/op {:action :create :tool :claude :kind :skills
                                      :project :example :id :wrap-up
                                      :summary "s"}) {})]
    (is (str/includes? out "projects/example skills/wrap-up")))
  ;; an id that already carries the project does not repeat it
  (let [out (plan/render-op (plan/op {:action :create :tool :claude :kind :mcps
                                      :project :example :id :example/slack
                                      :summary "s"}) {})]
    (is (str/includes? out "projects/example mcps/slack"))
    (is (not (str/includes? out "example/example")))))

(deftest a-projects-skills-land-in-the-project
  (let [dir (temp-dir)
        pack (str dir "/superpowers")
        _ (doseq [n ["brainstorming" "writing-plans"]]
            (fs/create-dirs (str pack "/skills/" n))
            (spit (str pack "/skills/" n "/SKILL.md") "---\nname: x\n---\n"))
        own (str dir "/agent-pack/skills/wrap-up")
        _ (do (fs/create-dirs own) (spit (str own "/SKILL.md") "---\nname: wrap-up\n---\n"))
        cfg (config/normalize {:skills {:wrap-up {:from :agent-pack}
                                        :loose {:path own}}
                               :skill-packs {:superpowers {:uri (str "file://" pack) :dir "skills"}
                                             :agent-pack {:uri (str "file://" dir "/agent-pack")}}
                               :projects {:example {:path (str dir "/example")
                                                      :executors #{:claude}
                                                      :skills [:superpowers :wrap-up :nonesuch]}}}
                              "x")
        proj (get-in cfg [:projects :example])
        {:keys [skills pending unknown]} (sources/project-skills cfg proj)]
    (testing "naming a pack asks for every skill in it"
      (is (= #{:brainstorming :writing-plans :wrap-up} (set (keys skills)))))
    (testing "an id that names nothing is reported, not dropped"
      (is (= [:nonesuch] unknown))
      (is (empty? pending)))
    (testing "a skill a project named is that project's, not the machine's"
      (is (= :project (get-in cfg [:skills :wrap-up :scope])))
      (is (= :global (get-in cfg [:skills :loose :scope]))))
    (testing "an unfetched pack can enumerate nothing and says so"
      ;; :nonesuch turns pending too, not unknown — it might yet be a bare
      ;; skill name hiding inside the pack that has not been cloned
      (let [cfg2 (assoc-in cfg [:skill-packs :superpowers :root] (str dir "/not-cloned"))]
        (is (= #{:nonesuch :superpowers} (set (:pending (sources/project-skills cfg2 proj)))))
        (is (empty? (:unknown (sources/project-skills cfg2 proj))))))))

(deftest a-bare-skill-name-resolves-without-a-skills-entry
  (let [dir (temp-dir)
        pack-a (str dir "/pack-a")
        pack-b (str dir "/pack-b")
        _ (doseq [[p n] [[pack-a "wrap-up"] [pack-b "shared"] [pack-a "shared"]]]
            (fs/create-dirs (str p "/skills/" n))
            (spit (str p "/skills/" n "/SKILL.md") "---\nname: x\n---\n"))
        cfg (config/normalize {:skill-packs {:a {:uri (str "file://" pack-a)}
                                             :b {:uri (str "file://" pack-b)}}
                               :projects {:example {:path (str dir "/example")
                                                      :executors #{:claude :codex}
                                                      :skills [:wrap-up :shared]}}}
                              "x")
        proj (get-in cfg [:projects :example])
        {:keys [skills ambiguous unknown]} (sources/project-skills cfg proj)]
    (testing "found in exactly one pack — no :skills entry needed at all"
      (is (contains? skills :wrap-up))
      (is (str/ends-with? (:source (:wrap-up skills)) "pack-a/skills/wrap-up")))
    (testing "found in more than one pack is reported, not guessed"
      (is (= 1 (count ambiguous)))
      (is (= :shared (:id (first ambiguous))))
      (is (= #{:a :b} (set (:packs (first ambiguous)))))
      (is (empty? unknown)))
    (testing "structural-findings agrees: no entry needed, ambiguity is an error"
      (let [findings (config/structural-findings cfg)]
        (is (not-any? #(str/includes? (:message %) "wrap-up") findings))
        (is (some #(and (= :error (:level %)) (str/includes? (:message %) "shared")
                       (str/includes? (:message %) "more than one pack"))
                  findings))))
    (testing "codex has no project skills directory, so a bare-resolved skill still reaches it user-wide"
      ;; regression: dropping the :skills entry must not silently stop codex
      ;; (and pi/omp, same shape) from installing a skill it only ever
      ;; discovered by naming a project's executors — see bare-project-skills
      (is (contains? (:skills cfg) :wrap-up))
      (is (contains? (:tools (:wrap-up (:skills cfg))) :codex))
      (is (contains? (set (core/inventory cfg)) [:codex :skills :wrap-up]))
      (is (some #(and (= :create (:action %)) (= :wrap-up (:id %)))
                (codex/skill-ops cfg state/empty-state))))))

(deftest a-project-skill-is-owned-where-it-was-installed
  (let [dir (temp-dir)
        src (str dir "/skills/wrap-up")
        _ (do (fs/create-dirs src) (spit (str src "/SKILL.md") "---\nname: wrap-up\n---\n"))
        cfg (config/normalize {:skills {:wrap-up {:path src}}
                               :projects {:proj {:path (str dir "/proj")
                                                 :executors #{:claude :codex}
                                                 :skills [:wrap-up]}}}
                              "x")
        inv (set (core/inventory cfg))]
    (testing "claude owns it under the project it belongs to"
      (is (contains? inv [:claude :skills :proj/wrap-up]))
      (is (not (contains? inv [:claude :skills :wrap-up]))))
    (testing "codex has no project skills directory, so its copy is user-wide"
      (is (contains? inv [:codex :skills :wrap-up])))))

(deftest a-server-left-behind-in-mcp-json-is-reported-not-deleted
  ;; local scope owning a server does not remove the copy the repo still ships
  (let [dir (temp-dir)
        proj (str dir "/proj")
        _ (do (fs/create-dirs proj)
              (u/write-json! (str proj "/.mcp.json")
                             {:mcpServers {:shared {:command "/bin/echo"
                                                    :env {:TOKEN "sk-live-abc"}}}}))
        cfg (config/normalize {:mcps {:shared {:cmd "/bin/echo hi" :tools [:claude]}}
                               :projects {:proj {:path proj :executors #{:claude}
                                                 :mcp [:shared]}}}
                              "x")
        ;; the adapter directly: `build-plan` skips a tool whose CLI is not
        ;; installed, and CI has no `claude` binary
        ops (claude/plan cfg state/empty-state)
        warn (first (filter :warn ops))
        out (plan/render-plan ops {})]
    (testing "the leftover copy is named, with its credential called out"
      (is (some? warn))
      (is (str/includes? (:summary warn) "also declared in"))
      (is (str/includes? (:summary warn) "carries a credential")))
    (testing "nothing plans to touch the file agentctl did not write"
      (is (empty? (filter #(and (str/includes? (str (:target %)) ".mcp.json")
                                (not= :noop (:action %)))
                          ops))))
    (is (not (str/includes? out "sk-live-abc")))))

;; ---------------------------------------------------------------- hooks

(deftest hook-group-shapes-a-declared-hook-into-the-native-entry
  (testing "a matcher and the fields the DSL knows about"
    (is (= {:matcher "Write|Edit"
            :hooks [{:type "command" :command "lock.sh" :timeout 25}]}
           (claude/hook-group {:event :PreToolUse :matcher "Write|Edit"
                               :command "lock.sh" :timeout 25}))))
  (testing "no matcher fires unconditionally, and is left out rather than written nil"
    (is (= {:hooks [{:type "command" :command "session-start.sh"}]}
           (claude/hook-group {:event :SessionStart :command "session-start.sh"}))))
  (testing ":async and the camelCase-only fields pass through by name"
    (is (= {:hooks [{:type "command" :command "x" :async true}]}
           (claude/hook-group {:event :Stop :command "x" :async true})))))

(deftest unknown-tool-settings-still-warn-but-hooks-no-longer-do
  (let [cfg (config/normalize {:executors {:claude {:hooks {:x {:event :Stop :command "x"}}
                                                    :bogus-setting true}}
                               :projects {:p {:path "/tmp/p" :executors #{:claude}}}}
                              "x")
        ops (claude/settings-ops cfg)
        warns (map :summary (filter :warn ops))]
    (is (not-any? #(str/includes? % "unsupported setting :hooks") warns))
    (is (some #(str/includes? % "unsupported setting :bogus-setting") warns))))

(deftest model-settings-and-enable-workflows-map-to-their-native-keys
  (let [cfg (config/normalize {:executors {:claude {:model-settings {:claude-sonnet-5 {:effort-level "high"}}
                                                    :on #{:enable-workflows}}}
                               :projects {:p {:path "/tmp/p" :executors #{:claude}}}}
                              "x")
        ops (claude/settings-ops cfg)
        diff-for (fn [k] (first (keep #(some (fn [d] (when (= k (:key d)) d)) (:diffs %)) ops)))]
    (is (= {:claude-sonnet-5 {:effort-level "high"}} (:after (diff-for :modelSettings))))
    (is (true? (:after (diff-for :enableWorkflows))))))

(deftest hooks-are-owned-in-the-manifest-with-enough-to-find-them-again
  (let [cfg (config/normalize {:executors {:claude {:hooks {:reminder {:event :SessionStart
                                                                       :matcher "startup"
                                                                       :command "reminder.sh"}}}}
                               :projects {:p {:path "/tmp/p" :executors #{:claude}}}}
                              "x")
        inv (into {} (map (fn [[t k id data]] [[t k id] data])) (core/inventory cfg))]
    (is (= {:path [:hooks :SessionStart]
            :value {:matcher "startup" :hooks [{:type "command" :command "reminder.sh"}]}}
           (get inv [:claude :hooks :reminder])))))

(deftest claude-hooks-round-trip-without-disturbing-a-third-partys-entries
  (let [dir (temp-dir) f (str dir "/settings.json")]
    (u/write-json! f {:hooks {:PreToolUse [{:matcher "*"
                                            :hooks [{:type "command" :command "orca-inject"}]}]}})
    (with-redefs [claude/settings-file f]
      (let [cfg1 (config/normalize
                  {:executors {:claude {:hooks {:reminder {:event :SessionStart :matcher "startup"
                                                           :command "reminder.sh"}
                                                :lock {:event :PreToolUse :matcher "Write|Edit"
                                                       :command "lock.sh" :timeout 25}}}}
                   :projects {:p {:path "/tmp/p" :executors #{:claude}}}}
                  "x")
            st0 state/empty-state
            ops1 (claude/hook-ops cfg1 st0)]
        (testing "both declared hooks create, the injected entry is not among the ops"
          (is (= #{:create} (set (map :action ops1))))
          (is (= #{:reminder :lock} (set (map :id ops1)))))
        (let [_ (doseq [op ops1] ((:exec! op)))
              st1 (core/sync-state! st0 cfg1 #{})]
          (testing "the array now holds all three, in append order"
            (is (= ["orca-inject" "lock.sh" "reminder.sh"]
                   (map #(get-in % [:hooks 0 :command])
                        (concat (get-in (u/read-json f) [:hooks :PreToolUse])
                                (get-in (u/read-json f) [:hooks :SessionStart]))))))
          (testing "a second pass with the same config is fully converged"
            (is (empty? (claude/hook-ops cfg1 st1))))
          (testing "changing one hook's command updates it in place"
            (let [cfg2 (config/normalize
                        {:executors {:claude {:hooks {:reminder {:event :SessionStart :matcher "startup"
                                                                 :command "reminder.sh"}
                                                      :lock {:event :PreToolUse :matcher "Write|Edit"
                                                             :command "lock-v2.sh" :timeout 25}}}}
                         :projects {:p {:path "/tmp/p" :executors #{:claude}}}}
                        "x")
                  ops2 (claude/hook-ops cfg2 st1)]
              (is (= [[:update :lock]] (map (juxt :action :id) ops2)))
              (doseq [op ops2] ((:exec! op)))
              (is (= ["orca-inject" "lock-v2.sh"]
                     (map #(get-in % [:hooks 0 :command]) (get-in (u/read-json f) [:hooks :PreToolUse]))))
              (testing "dropping a hook from agents.edn prunes only that element"
                (let [st2 (core/sync-state! st1 cfg2 #{})
                      cfg3 (config/normalize
                            {:executors {:claude {:hooks {:reminder {:event :SessionStart :matcher "startup"
                                                                     :command "reminder.sh"}}}}
                             :projects {:p {:path "/tmp/p" :executors #{:claude}}}}
                            "x")
                      ops3 (claude/hook-ops cfg3 st2)]
                  (is (= [[:delete :lock]] (map (juxt :action :id) ops3)))
                  (doseq [op ops3] ((:exec! op)))
                  (let [after (u/read-json f)]
                    (is (= ["orca-inject"]
                           (map :command (mapcat :hooks (get-in after [:hooks :PreToolUse])))))
                    (is (= ["reminder.sh"]
                           (map :command (mapcat :hooks (get-in after [:hooks :SessionStart])))))))))))))))

(deftest a-scoped-run-that-plans-nothing-for-a-hook-does-not-forget-its-live-value
  ;; `-t`/`-k`/`-p` can filter a hook's op out of the plan entirely while the
  ;; id stays declared in cfg, so `inventory` still calls it owned. Passing
  ;; `done` tells sync-state! this run never wrote it — the manifest must keep
  ;; the value it already recorded rather than stamp the new declaration over
  ;; it sight-unseen, or the next unfiltered run loses track of the real
  ;; element and orphans it in the array.
  (let [cfg1 (config/normalize
              {:executors {:claude {:hooks {:lock {:event :PreToolUse :matcher "Write|Edit"
                                                    :command "lock.sh"}}}}}
              "x")
        st1 (core/sync-state! state/empty-state cfg1 #{} #{[:claude :hooks :lock]})
        cfg2 (config/normalize
              {:executors {:claude {:hooks {:lock {:event :PreToolUse :matcher "Write|Edit"
                                                    :command "lock-v2.sh"}}}}}
              "x")]
    (testing "a run that actually wrote it records the new value"
      (is (= "lock.sh" (get-in (state/entry st1 :claude :hooks :lock)
                                [:value :hooks 0 :command]))))
    (testing "a scoped run that planned nothing for this id (empty done) keeps the old value"
      (let [st2 (core/sync-state! st1 cfg2 #{} #{})]
        (is (= "lock.sh" (get-in (state/entry st2 :claude :hooks :lock)
                                  [:value :hooks 0 :command])))))
    (testing "an unscoped run (done nil) is free to overwrite it"
      (let [st3 (core/sync-state! st1 cfg2 #{})]
        (is (= "lock-v2.sh" (get-in (state/entry st3 :claude :hooks :lock)
                                     [:value :hooks 0 :command])))))))

(deftest scoped?-is-what-tells-callers-which-arm-of-sync-state-to-use
  ;; main.clj / gui.clj pass `(when (core/scoped? opts) done-set)` — get this
  ;; wrong and every unfiltered `apply!` either never refreshes a hand-edited
  ;; hook's stored value (stuck on `nil`) or, worse, a scoped run refreshes
  ;; everything as if unfiltered (stuck on `false`/never-nil).
  (is (false? (core/scoped? {})))
  (is (true? (core/scoped? {:tools [:claude]})))
  (is (true? (core/scoped? {:kinds [:hooks]})))
  (is (true? (core/scoped? {:projects #{"p"}}))))

(defn- scope-cfg
  "A workspace with two projects and one nested inside the first, so a cwd can
   sit in a project, in the workspace, or nowhere near either."
  [ws]
  (doseq [d ["agentctl" "agent" "agentctl/vendor/inner" "elsewhere"]]
    (fs/create-dirs (str ws "/" d)))
  (config/parse-config
   (str "{:projects {:agentctl {:path \"" ws "/agentctl\" :executors {:claude {}}}\n"
        "            :agent    {:path \"" ws "/agent\" :executors {:claude {}}}\n"
        "            :inner    {:path \"" ws "/agentctl/vendor/inner\" :executors {:claude {}}}}}")
   (str ws "/agents.edn")))

(deftest cwd-scope-reads-the-working-directory-as-a-scope
  (let [ws (str (temp-dir) "/ws")
        cfg (scope-cfg ws)
        at (fn [d] (core/cwd-scope cfg d))]
    (testing "standing in a project — or anywhere under it — is that project"
      (is (= {:projects #{:agentctl} :where :project} (dissoc (at (str ws "/agentctl")) :path)))
      (is (= #{:agentctl} (:projects (at (str ws "/agentctl/src/deep"))))))
    (testing "a project nested inside another wins: it is the closer answer"
      (is (= #{:inner} (:projects (at (str ws "/agentctl/vendor/inner/src"))))))
    (testing "a shared name prefix is not containment"
      (is (= #{:agent} (:projects (at (str ws "/agent"))))
          "~/ws/agent must not be swallowed by ~/ws/agentctl"))
    (testing "the workspace the projects are filed under is all of them"
      (is (= {:projects #{:agentctl :agent :inner} :where :root} (dissoc (at ws) :path))))
    (testing "containment outranks holding projects: vendor is inside agentctl"
      (is (= #{:agentctl} (:projects (at (str ws "/agentctl/vendor"))))))
    (testing "a directory in the workspace with no project under it narrows nothing"
      (is (nil? (at (str ws "/elsewhere")))))
    (testing "everything else plans the whole config, as it always did"
      (is (nil? (at "/tmp"))))))

(deftest home-is-never-the-workspace-however-the-projects-are-spread
  ;; projects under ~/projects and ~/dotfiles make home their common parent by
  ;; arithmetic alone. Reading that as a workspace would make a bare `apply`
  ;; from home skip every global setting, server and provider unasked.
  (let [ws (str u/home "/spread")]
    (doseq [d ["spread/one" "dots"] ] (fs/create-dirs (str u/home "/" d)))
    (let [cfg (config/parse-config
               (str "{:projects {:one  {:path \"" u/home "/spread/one\" :executors {:claude {}}}\n"
                    "            :dots {:path \"" u/home "/dots\" :executors {:claude {}}}}}")
               (str u/home "/agents.edn"))]
      (is (nil? (core/cwd-scope cfg u/home)))
      (is (nil? (core/cwd-scope cfg "/")))
      (is (= #{:one} (:projects (core/cwd-scope cfg ws)))
          "a real workspace below home still scopes"))))

(deftest a-project-scoped-run-still-fetches-the-packs-that-project-needs
  ;; a pack is cloned once for the machine, so its op carries no :project.
  ;; Filtered out, `apply!` inside a project could never install a skill the
  ;; project asks for out of a pack that is not on disk yet — and converge!'s
  ;; second pass would never fire either.
  (let [cfg (config/parse-config
             (str "{:skill-packs {:mine {:uri \"https://example.invalid/mine\"}\n"
                  "               :theirs {:uri \"https://example.invalid/theirs\"}}\n"
                  " :skills {:named {:from :theirs :tools [:claude]}}\n"
                  " :projects {:p {:path \"/tmp/agentctl-scope-p\" :executors {:claude {}}\n"
                  "                :skills [:mine]}\n"
                  "            :q {:path \"/tmp/agentctl-scope-q\" :executors {:claude {}}\n"
                  "                :skills [:named]}}}")
             "/tmp/agentctl-scope/agents.edn")]
    (is (= #{:mine} (sources/packs-for cfg #{:p})) "a whole pack named by the project")
    (is (= #{:theirs} (sources/packs-for cfg #{:q})) "the pack behind a declared skill")
    (is (= #{:mine :theirs} (sources/packs-for cfg #{:p :q})))
    (is (= #{} (sources/packs-for cfg #{})))))

(deftest project-scoped-hooks-warn-instead-of-vanishing-silently
  ;; the DSL has no project scope for hooks (Claude Code's hooks live in one
  ;; settings.json per project, but agentctl only manages the global one
  ;; today) — declaring `:hooks` under a project's executor must not just
  ;; disappear with no trace
  (let [cfg (config/normalize
             {:projects {:p {:path "/tmp/p"
                             :executors {:claude {:hooks {:x {:event :Stop :command "x"}}}}}}}
             "x")
        ops (claude/project-ops cfg state/empty-state)
        warns (filter :warn ops)]
    (is (some #(str/includes? (:summary %) "hooks have no project scope") warns))))

(deftest hook-declarations-need-an-event-and-a-command
  (let [msgs (map :message
                  (config/structural-findings
                   (config/normalize {:executors {:claude {:hooks {:bad {:matcher "*"}}}}}
                                     "x")))]
    (is (some #(str/includes? % "needs :event") msgs))
    (is (some #(str/includes? % "needs :command") msgs))))

(deftest hooks-grouped-by-event-normalize-like-the-flat-form
  (let [cfg (config/normalize
             {:executors
              {:claude
               {:hooks {:SessionStart [{:id :reminder :matcher "startup" :command "reminder.sh"}
                                       {:id :moshi-hook :command "moshi-hook" :async true}]
                        ;; the flat, id-keyed form still works in the same map
                        :lock {:event :PreToolUse :matcher "Write|Edit" :command "lock.sh"}}}}}
             "x")
        hooks (get-in cfg [:tools :claude :hooks])]
    (testing "each grouped entry becomes a flat, id-keyed hook with :event filled in"
      (is (= {:matcher "startup" :command "reminder.sh" :event :SessionStart}
             (:reminder hooks)))
      (is (= {:command "moshi-hook" :async true :event :SessionStart}
             (:moshi-hook hooks))))
    (testing "the flat form beside it is untouched"
      (is (= {:event :PreToolUse :matcher "Write|Edit" :command "lock.sh"} (:lock hooks))))
    (testing "hook-ops plans all three the same way regardless of which form declared them"
      (is (= #{:reminder :moshi-hook :lock}
             (set (map :id (claude/hook-ops cfg state/empty-state))))))
    (testing "structural-findings has nothing to say when every entry has an :id"
      (is (empty? (config/structural-findings cfg))))))

(deftest a-grouped-hook-missing-an-id-is-a-structural-error
  (let [cfg (config/normalize
             {:executors {:claude {:hooks {:SessionStart [{:command "no-id.sh"}]}}}}
             "x")
        msgs (map :message (config/structural-findings cfg))]
    (is (some #(str/includes? % "needs :id") msgs))
    ;; a synthesized placeholder id keeps normalization from colliding two
    ;; id-less hooks onto the same key — it still shows up, not silently
    ;; dropped, so the finding above has something to point at
    (is (contains? (get-in cfg [:tools :claude :hooks]) :SessionStart-0))))

(deftest a-key-that-names-an-env-var-is-not-a-plaintext-secret
  ;; `:bearer-token-env "MCP_BEARER_TOKEN"` holds the variable's name, not the
  ;; token — flagging it teaches the reader to skip the warning that matters
  (let [findings #(map :message
                       (validate/check-config-secrets
                        (config/normalize % "agents.edn")))]
    (testing "indirection keys are quiet"
      (is (empty? (findings {:mcps {:remote {:url "https://notes.example.com/mcp"
                                             :bearer-token-env "MCP_BEARER_TOKEN"}}})))
      (is (empty? (findings {:extra-providers
                             {:gateway {:url "https://api.example.com/v1"
                                        :key-name "gateway-llm-cli"}}}))))
    (testing "an actual literal still gets caught"
      (is (seq (findings {:mcps {:remote {:url "https://notes.example.com/mcp"
                                          :env {"API_TOKEN" "abcdef0123456789"}}}}))))))

(deftest removing-a-json-entry-leaves-its-neighbours-alone
  (let [dir (temp-dir) f (str dir "/claude.json")]
    (u/write-json! f {:projects {:proj {:mcpServers {:gone {:command "/bin/echo"}
                                                    :stays {:command "/bin/cat"}}
                                        :allowedTools ["Bash"]}}})
    (let [op (plan/json-unset-op {:tool :claude :kind :mcps :id :proj/gone
                                  :file f :path [:projects :proj :mcpServers :gone]
                                  :summary "removed from agents.edn"})]
      (is (= :delete (:action op)))
      ((:exec! op))
      (let [after (u/read-json f)]
        (testing "only the named entry goes"
          (is (nil? (get-in after [:projects :proj :mcpServers :gone])))
          (is (some? (get-in after [:projects :proj :mcpServers :stays])))
          (is (= ["Bash"] (get-in after [:projects :proj :allowedTools]))))))
    (testing "an entry already gone is not an op"
      (is (nil? (plan/json-unset-op {:tool :claude :kind :mcps :id :proj/gone
                                     :file f
                                     :path [:projects :proj :mcpServers :gone]}))))))

(deftest json-array-merge-op-keeps-other-elements-untouched
  (let [dir (temp-dir) f (str dir "/settings.json")]
    (u/write-json! f {:hooks {:PreToolUse [{:matcher "*" :hooks [{:type "command" :command "orca-inject"}]}]}})
    (testing "a fresh id creates, appended after whatever is already there"
      (let [op (plan/json-array-merge-op
                {:tool :claude :kind :hooks :id :lock
                 :file f :path [:hooks :PreToolUse]
                 :value {:matcher "Write|Edit" :hooks [{:type "command" :command "lock.sh"}]}})]
        (is (= :create (:action op)))
        ((:exec! op))
        (let [after (get-in (u/read-json f) [:hooks :PreToolUse])]
          (is (= 2 (count after)))
          (is (= "orca-inject" (get-in (first after) [:hooks 0 :command])))
          (is (= "lock.sh" (get-in (second after) [:hooks 0 :command]))))))
    (testing "the value already present is a noop, and untouched otherwise"
      (is (nil? (plan/json-array-merge-op
                 {:tool :claude :kind :hooks :id :lock
                  :file f :path [:hooks :PreToolUse]
                  :value {:matcher "Write|Edit" :hooks [{:type "command" :command "lock.sh"}]}}))))
    (testing "a changed command replaces the old element in place, not alongside it"
      (let [op (plan/json-array-merge-op
                {:tool :claude :kind :hooks :id :lock
                 :file f :path [:hooks :PreToolUse]
                 :old {:matcher "Write|Edit" :hooks [{:type "command" :command "lock.sh"}]}
                 :value {:matcher "Write|Edit" :hooks [{:type "command" :command "lock-v2.sh"}]}})]
        (is (= :update (:action op)))
        ((:exec! op))
        (let [after (get-in (u/read-json f) [:hooks :PreToolUse])]
          (is (= 2 (count after)))
          (is (= "orca-inject" (get-in (first after) [:hooks 0 :command])))
          (is (= "lock-v2.sh" (get-in (second after) [:hooks 0 :command]))))))
    (testing "a stale `old` already gone by hand is not mistaken for the wrong neighbour"
      (let [op (plan/json-array-merge-op
                {:tool :claude :kind :hooks :id :gone
                 :file f :path [:hooks :PreToolUse]
                 :old {:matcher "Write|Edit" :hooks [{:type "command" :command "lock.sh"}]}
                 :value {:matcher "Grep|Glob" :hooks [{:type "command" :command "gate.sh"}]}})]
        (is (= :create (:action op)))
        ((:exec! op))
        (is (= 3 (count (get-in (u/read-json f) [:hooks :PreToolUse]))))))))

(deftest json-array-unset-op-removes-only-its-own-element
  (let [dir (temp-dir) f (str dir "/settings.json")]
    (u/write-json! f {:hooks {:PreToolUse [{:matcher "*" :hooks [{:type "command" :command "orca-inject"}]}
                                           {:matcher "Write|Edit" :hooks [{:type "command" :command "lock.sh"}]}]}})
    (let [op (plan/json-array-unset-op
              {:tool :claude :kind :hooks :id :lock
               :file f :path [:hooks :PreToolUse]
               :old {:matcher "Write|Edit" :hooks [{:type "command" :command "lock.sh"}]}})]
      (is (= :delete (:action op)))
      ((:exec! op))
      (let [after (get-in (u/read-json f) [:hooks :PreToolUse])]
        (is (= 1 (count after)))
        (is (= "orca-inject" (get-in (first after) [:hooks 0 :command])))))
    (testing "an element already gone is not an op"
      (is (nil? (plan/json-array-unset-op
                 {:tool :claude :kind :hooks :id :lock
                  :file f :path [:hooks :PreToolUse]
                  :old {:matcher "Write|Edit" :hooks [{:type "command" :command "lock.sh"}]}}))))))

(deftest a-checked-setting-that-already-agrees-is-one-line
  (let [dir (temp-dir) f (str dir "/settings.json")]
    (spit f "{\"ultracode\": true, \"skipAutoPermissionPrompt\": true}")
    (let [ops (for [[id k] [[:ultracode :ultracode] [:skip-auto :skipAutoPermissionPrompt]]]
                (plan/json-set-op {:tool :claude :kind :settings :id id
                                   :file f :path [k] :value true
                                   :report-converged? true}))
          out (plan/render-plan ops {})]
      (is (every? #(= :noop (:action %)) ops))
      (testing "converged checks collapse into one line and say nothing else"
        (is (str/includes? out "= settings/{skip-auto ultracode}"))
        (is (not (str/includes? out "edit `"))))
      (testing "without opting in, a converged check is not an op at all"
        (is (nil? (plan/json-set-op {:tool :claude :kind :settings :id :ultracode
                                     :file f :path [:ultracode] :value true})))))))

(deftest a-link-op-prints-the-command-it-runs
  (let [dir (temp-dir)
        src (str dir "/src") dest (str dir "/dest")
        _ (fs/create-dirs src)
        out (plan/render-op (plan/link-op {:tool :claude :kind :skills :id :wrap-up
                                           :src src :dest dest :mode :symlink}) {})]
    (is (str/includes? out (str "!ln -s " (u/tilde src) " " (u/tilde dest))))
    (testing "replacing an existing link says so in the command"
      (fs/create-sym-link dest (str dir "/elsewhere"))
      (let [out (plan/render-op (plan/link-op {:tool :claude :kind :skills :id :wrap-up
                                               :src src :dest dest :mode :symlink}) {})]
        (is (str/includes? out "!ln -sfn "))))))

(deftest a-plan-never-prints-a-live-credential
  ;; plans get pasted into tickets and chat
  (let [out (plan/render-op (plan/op {:action :update :tool :claude :kind :mcps :id :slack
                                      :diffs [{:key :SLACK_MCP_XOXC_TOKEN
                                               :before nil
                                               :after "xoxc-111111111111-222222222222"}]}) {})]
    (is (not (str/includes? out "111111111111")))
    (is (str/includes? out "xoxc…22")))
  (let [out (plan/render-op (plan/op {:action :update :tool :claude :kind :mcps :id :slack
                                      :diffs [{:key :command :before "a" :after "bun"}]}) {})]
    (is (str/includes? out "\"bun\"")))
  ;; a git revision is 40 hex characters and is not a secret
  (let [sha "b36e0829c6d0140e93cfef2ca599b1b07d4a7797"
        out (plan/render-op (plan/op {:action :update :tool :agentctl :kind :skill-packs
                                      :id :superpowers
                                      :diffs [{:key :revision :before nil :after sha}]}) {})]
    (is (str/includes? out sha))
    (is (refs/secret-shaped? nil sha))
    (is (not (refs/credential? nil sha)))))

(deftest a-pack-is-named-the-way-people-name-it
  (let [root (str (temp-dir) "/superpowers")
        cfg {:skill-packs {:superpowers {:type :git
                                         :uri "https://github.com/example/agent-skills"
                                         :root root}}}
        [op] (sources/pack-ops cfg)
        out (plan/render-op op {})]
    (is (str/includes? (:summary op) "gh: clone pack `example/agent-skills`"))
    (testing "agentctl runs the plan, it is not a tool the plan configures"
      (is (str/includes? out "+ skill-packs/superpowers"))
      (is (not (str/includes? out "agentctl/skill-packs"))))
    (testing "the line is the command that runs, marked as one"
      (let [cmd (->> (str/split-lines out) (filter #(str/starts-with? (str/trim %) "!")) first)]
        (is (some? cmd))
        (is (= (str/trim cmd)
               (str "!" (str/join " " (map u/tilde (first (:cmds op)))))))
        (is (str/includes? cmd "example/agent-skills"))
        (is (re-find #"^\s*!(gh repo clone|git clone)" cmd))))))

(deftest a-symlink-reads-from-source-to-destination
  (let [dir (temp-dir)
        src (str dir "/src-skill")
        _ (fs/create-dirs src)
        op (plan/link-op {:tool :claude :kind :skills :id :demo
                          :src src :dest (str dir "/dest-skill")})]
    (is (str/includes? (:summary op) (str "symlink " (u/tilde src) " -> " (u/tilde (str dir "/dest-skill")))))))


;; ---------------------------------------------------------------- gui

(def gui-config
  (str "{:executors {:pi {:model \"m1\" :provider \"prov\"}}\n"
       " :extra-providers {:prov {:url \"http://127.0.0.1:9999\" :models [\"m1\"] :tools [:pi]}}\n"
       " :projects {:p {:path \"/tmp/agentctl-gui-p\" :executors {:pi {}}}}}\n"))

;; ---------------------------------------------------------------- edit / form

(def hand-written
  (str "{;; what this machine runs\n"
       " :#def {effort \"high\"}\n"
       "\n"
       " :executors\n"
       " {:claude {:model \"sonnet\" :thinking $effort} ;; the default\n"
       "  :codex {:model \"gpt\" :thinking $effort}}\n"
       "\n"
       " :mcps {:search \"/usr/local/bin/search-mcp --stdio\"}}\n"))

(deftest edit-changes-one-node-and-leaves-the-file-alone
  (let [out (edit/apply-ops hand-written
                            [{:op :set :path [:executors :claude :model] :value "opus"}])]
    (is (str/includes? out "{:model \"opus\" :thinking $effort}"))
    (is (str/includes? out ";; what this machine runs") "comments survive an edit")
    (is (str/includes? out ";; the default"))
    (testing "a $name reference is a reference, not the value it happens to expand to"
      (is (= 2 (count (re-seq #"\$effort" out)))
          "editing one tool must not bake the binding into the other"))))

(deftest edit-writes-a-new-key-the-way-the-file-is-written
  (let [out (edit/apply-ops hand-written
                            [{:op :set :path [:executors :pi :model] :value "coder"}
                             {:op :set :path [:skills :review :from] :value :pack}])]
    (is (str/includes? out "\n  :pi {:model \"coder\"}") "indented like its siblings")
    (is (str/includes? out "\n\n :skills {:review {:from :pack}}")
        "a new section keeps the blank line between sections")
    (is (map? (edit/parse out)))))

(deftest edit-removes-a-key-and-a-whole-entry
  (is (not (str/includes? (edit/apply-ops hand-written
                                          [{:op :unset :path [:executors :claude :thinking]}])
                          "{:model \"sonnet\" :thinking")))
  (let [out (edit/apply-ops hand-written [{:op :unset :path [:executors :codex]}])]
    (is (nil? (get-in (edit/parse out) [:executors :codex])))
    (is (some? (get-in (edit/parse out) [:executors :claude])))))

(deftest edit-starts-from-nothing
  (testing "gui on a machine with no agents.edn opens on a blank page that still edits"
    (is (= "{:mcps {:x {:cmd \"y\"}}}"
           (edit/apply-ops "" [{:op :set :path [:mcps :x :cmd] :value "y"}])))))

(deftest edit-expands-shorthand-once-and-keeps-the-edits-around-it
  (testing "the controls re-send the expansion until they get a re-render, so a
            repeat must not overwrite the fields set in between"
    (let [out (edit/apply-ops hand-written
                              [{:op :expand :path [:mcps :search]
                                :value {:cmd "/usr/local/bin/search-mcp --stdio"}}
                               {:op :set :path [:mcps :search :cwd] :value "/tmp"}
                               {:op :expand :path [:mcps :search]
                                :value {:cmd "/usr/local/bin/search-mcp --stdio"}}
                               {:op :set :path [:mcps :search :tools] :value [:claude]}])
          m (get-in (edit/parse out) [:mcps :search])]
      (is (= "/tmp" (:cwd m)) "the field set between two expansions survives")
      (is (= [:claude] (:tools m)))
      (is (= "/usr/local/bin/search-mcp --stdio" (:cmd m))))))

(deftest edit-refuses-what-it-cannot-do
  (is (thrown? Exception (edit/apply-ops "{" [{:op :set :path [:a] :value 1}])))
  (is (thrown? Exception (edit/apply-ops "{}" [{:op :set :path [] :value 1}])))
  (is (thrown? Exception (edit/apply-ops "{}" [{:op :frobnicate :path [:a] :value 1}]))))

(deftest form-describes-the-buffer-as-written
  (let [m (form/model hand-written)
        section (fn [k] (first (filter #(= k (:key %)) (:sections m))))
        entry (fn [k id] (first (filter #(= id (:id %)) (:entries (section k)))))
        field (fn [e k] (first (filter #(= k (:key %)) (:fields e))))]
    (is (true? (:ok m)))
    (testing "a field bound to a :#def shows the reference, so overwriting it is deliberate"
      (is (= "$effort" (:value (field (entry ":executors" ":claude") "thinking")))))
    (testing "the fields on offer are the ones the adapter actually writes"
      (is (= (conj (set (map name (form/settings-catalog :claude))) "features")
             (set (map :key (:fields (entry ":executors" ":claude")))))
          "plus the feature switches, which are :on/:off over the boolean ones"))
    (testing "a tool the file says nothing about is offered, not hidden"
      (is (false? (:declared (entry ":executors" ":omp")))))
    (testing "a bare command line is shown as the map it is shorthand for"
      (let [e (entry ":mcps" ":search")]
        (is (= "/usr/local/bin/search-mcp --stdio" (:value (field e "cmd"))))
        (is (= "{:cmd \"/usr/local/bin/search-mcp --stdio\"}" (:shorthand e))
            "and the first edit carries the expansion with it")))
    (testing "a key the schema does not model is still editable, as EDN"
      (let [e (entry ":executors" ":claude")]
        (is (empty? (:extra e)))
        (is (= "edn" (:type (field e "env"))))))
    (is (= [":#def" "effort"] (:path (entry ":#def" "effort")))
        "a :#def binding is a symbol key, not a keyword")))

(deftest form-answers-a-half-typed-buffer-with-a-message
  (let [m (form/model "{:mcps {")]
    (is (false? (:ok m)))
    (is (str/includes? (:error m) "does not parse"))))

(deftest form-coerces-widget-values-into-edn
  (is (= "opus" (form/coerce :scalar "opus")))
  (is (= '$effort (form/coerce :scalar "$effort")) "a whole $name is the reference spelling")
  (is (= "~/$workspace/x" (form/coerce :scalar "~/$workspace/x")) "…but interpolation is a string")
  (is (= [:claude :codex] (form/coerce :kw-set [":claude" "codex"])))
  (is (= :local (form/coerce :enum ":local")))
  (is (= {"TOKEN" "!bw://a/b/c"} (form/coerce :edn "{\"TOKEN\" \"!bw://a/b/c\"}")))
  (is (thrown? Exception (form/coerce :edn "{oops")))
  (testing "an emptied field removes the key rather than writing nil"
    (is (= [{:op :unset :path [:mcps :x :cwd]}]
           (form/ops->edits [{:op "set" :path [":mcps" ":x" ":cwd"] :type "scalar" :value ""}])))))

(deftest edit-renames-a-key-in-place
  (let [out (edit/apply-ops hand-written
                            [{:op :rename :path [:#def 'effort] :value 'level}])]
    (is (str/includes? out ";; what this machine runs") "the comment above it stays")
    (is (str/includes? out " :#def {level \"high\"}") "and so does its line and position")
    (is (= {'level "high"} (:#def (edit/parse out)))))
  (testing "renaming a binding does not chase the $name references it had"
    ;; deliberate: the plan reports the now-unbound $effort as a warning, and
    ;; rewriting text the user did not point at is not what a rename is
    (is (str/includes? (edit/apply-ops hand-written
                                       [{:op :rename :path [:#def 'effort] :value 'level}])
                       "$effort")))
  (testing "a rename onto a name already in the map is refused, not written"
    ;; a duplicate key is not readable, and the pane that would report it is
    ;; the one that could no longer render
    (is (thrown-with-msg? Exception #"already declared"
                          (edit/apply-ops "{:#def {a 1 b 2}}"
                                          [{:op :rename :path [:#def 'a] :value 'b}]))))
  (is (thrown-with-msg? Exception #"not declared"
                        (edit/apply-ops hand-written
                                        [{:op :rename :path [:mcps :nope] :value :x}]))))

(deftest form-refuses-a-name-it-cannot-write
  (is (thrown-with-msg? Exception #"cannot be empty"
                        (form/ops->edits [{:op "rename" :path [":#def" "a"] :value "  "}])))
  (testing "a name with a space would otherwise be read as its first token"
    (is (thrown-with-msg? Exception #"not a usable name"
                          (form/ops->edits [{:op "rename" :path [":#def" "a"] :value "a b"}]))))
  (testing "the section's own id spelling decides symbol or keyword"
    (is (= {:op :rename :path [:#def 'a] :value 'b}
           (first (form/ops->edits [{:op "rename" :path [":#def" "a"] :value "b"}]))))
    (is (= {:op :rename :path [:mcps :search] :value :find}
           (first (form/ops->edits [{:op "rename" :path [":mcps" ":search"] :value ":find"}]))))))

(deftest form-switches-features-through-on-and-off
  (let [m (form/model "{:executors {:claude {:on #{:ultracode} :off #{:auto-compact}}}}")
        claude (->> (:sections m) (filter #(= ":executors" (:key %))) first :entries
                    (filter #(= ":claude" (:id %))) first)
        flags (first (filter #(= "features" (:key %)) (:fields claude)))
        state (into {} (map (juxt :key :state)) (:options flags))]
    (testing "three states, because the DSL has three"
      (is (= "on" (state "ultracode")))
      (is (= "off" (state "auto-compact")))
      (is (= "" (state "skip-auto")) "unstated is not off — it leaves the tool's default alone"))
    (testing "the switches are the boolean settings the adapter declares"
      (is (= #{"auto-compact" "skip-auto" "ultracode" "enable-workflows"}
             (set (map :key (:options flags))))))
    (testing "both keys travel with the field: a flag moves between two nodes"
      (is (= [":executors" ":claude" ":on"] (:path flags)))
      (is (= [":executors" ":claude" ":off"] (:off-path flags))))
    (is (empty? (:extra claude)) ":on and :off are the switches, not leftover EDN"))
  (testing "a flag set is written the way the DSL writes it"
    (is (= "{:executors {:claude {:on #{:auto-compact :ultracode}}}}"
           (edit/apply-ops "{:executors {:claude {}}}"
                           (form/ops->edits
                            [{:op "set" :path [":executors" ":claude" ":on"]
                              :type "flag-set" :value ["ultracode" "auto-compact"]}
                             {:op "set" :path [":executors" ":claude" ":off"]
                              :type "flag-set" :value []}]))))))

(deftest form-offers-a-picker-only-where-the-schema-closes-the-set
  (let [field (fn [text tool k]
                (->> (form/model text) :sections (filter #(= ":executors" (:key %))) first :entries
                     (filter #(= (str tool) (:id %))) first :fields
                     (filter #(= k (:key %))) first))]
    (testing "claude's settings schema types model as a plain string"
      ;; a full model id is as valid as an alias, so a select would forbid
      ;; values the tool accepts
      (let [f (field "{:executors {:claude {:model \"opus\"}}}" :claude "model")]
        (is (= "scalar" (:type f)))
        (is (= ["opus" "sonnet" "haiku" "fable"] (:suggest f)))))
    (testing "…but effortLevel and theme are closed sets, and are offered as such"
      (is (= ["low" "medium" "high" "xhigh"]
             (:options (field "{:executors {:claude {:thinking \"high\"}}}" :claude "thinking"))))
      (is (= "str-enum" (:type (field "{:executors {:claude {:theme \"dark\"}}}" :claude "theme")))))
    (testing "a value the file already holds outside the set stays editable"
      (let [f (field "{:executors {:claude {:theme \"custom:mine\"}}}" :claude "theme")]
        (is (= "scalar" (:type f)))
        (is (= "custom:mine" (:value f)))
        (is (seq (:suggest f)) "the set becomes a suggestion rather than a choice")))
    (testing "a tool that publishes no schema keeps open text"
      (is (= "scalar" (:type (field "{:executors {:codex {:thinking \"high\"}}}" :codex "thinking")))))
    (is (= "high" (form/coerce :str-enum "high")) "and these are strings, as the tools write them")
    (is (nil? (form/coerce :str-enum "")))))

(deftest form-lists-a-packs-skills-as-switches
  (let [dir (temp-dir)
        pack (str dir "/pack")]
    (u/write-text! (str pack "/skills/review/SKILL.md") "# review")
    (u/write-text! (str pack "/skills/triage/SKILL.md") "# triage")
    (let [text (str "{:skill-packs {:kit {:uri \"" pack "\" :type :file}}\n"
                    " :skills {:review {:from :kit}}}")
          section (->> (form/model text) :sections (filter #(= ":skills" (:key %))) first)
          group (first (:groups section))
          on (into {} (map (juxt :label :on)) (:options group))]
      (is (= ":kit" (:id group)))
      (is (= {"review" true "triage" false} on)
          "every skill the pack has on disk, on when the file installs it")
      (is (= "{:from :kit}" (:edn (first (:options group))))
          "switching one on declares it as coming from this pack")
      (is (nil? (:note group))))
    (testing "a pack that is not on disk yet says so rather than showing nothing"
      (let [group (->> (form/model "{:skill-packs {:kit {:uri \"git@example.com:me/kit.git\"}}}")
                       :sections (filter #(= ":skills" (:key %))) first :groups first)]
        (is (empty? (:options group)))
        (is (str/includes? (:note group) "not on disk yet"))))))

(deftest form-lays-the-bindings-out-as-a-table
  (let [section (->> (form/model hand-written) :sections
                     (filter #(= ":#def" (:key %))) first)]
    (is (= "table" (:layout section)) "name and value, both editable, one row each")
    (is (= ["effort"] (map :label (:entries section))))))

(deftest gui-edits-the-buffer-and-never-the-file
  (let [dir (temp-dir)
        path (str dir "/agents.edn")
        _ (u/write-text! path hand-written)
        ctx {:tok "t" :path path :base-opts {} :lock (Object.)}
        body (fn [m] (java.io.ByteArrayInputStream. (.getBytes (json/generate-string m))))
        post (fn [m] (gui/handler ctx {:request-method :post :uri "/api/edit"
                                       :headers {"host" "127.0.0.1:1" "x-agentctl-token" "t"}
                                       :body (body m)}))
        res (post {:text hand-written
                   :ops [{:op "set" :path [":executors" ":claude" ":model"]
                          :type "scalar" :value "opus"}]})
        data (json/parse-string (:body res) true)]
    (is (= 200 (:status res)))
    (is (str/includes? (:text data) "\"opus\""))
    (is (str/includes? (:text data) "$effort"))
    (is (= hand-written (slurp path)) "an edit is a buffer, not a write")
    (is (true? (get-in data [:form :ok])) "the controls and the plan answer the same buffer")
    (is (string? (:summary data)))
    (testing "an unreadable value is a message, not a 500"
      (is (= 400 (:status (post {:text hand-written
                                 :ops [{:op "set" :path [":mcps" ":x" ":env"]
                                        :type "edn" :value "{oops"}]})))))))

(deftest gui-coerces-json-filters-into-keywords
  (testing "keep-projects compares keywords and does no coercion of its own"
    (let [o (gui/opts-from-json {:tools ["pi"] :kinds ["mcps"] :projects ["example"] :verbose true}
                                {:file "x"})]
      (is (= #{:pi} (:tools o)))
      (is (= #{:mcps} (:kinds o)))
      (is (= #{:example} (:projects o)))
      (is (true? (:verbose o)))
      (is (false? (:show-noop o)))
      (is (= "x" (:file o)) "base opts survive"))))

(deftest gui-plans-from-a-buffer-that-was-never-saved
  (let [dir (temp-dir)
        path (str dir "/agents.edn")
        p (gui/plan-for-text gui-config path {:tools #{:pi} :kinds #{} :projects #{}})]
    (is (true? (:ok p)))
    (is (not (u/exists? path)) "planning writes nothing, not even the config")
    (is (string? (:summary p)))
    (is (nil? (:diffs p)) "ops are rendered, never serialized")))

(deftest gui-answers-a-half-typed-file-with-a-message
  (testing "the live pane means most keystrokes see invalid edn — that is a payload, not a 500"
    (let [p (gui/plan-for-text "{:mcps {" "/tmp/agentctl-gui/agents.edn" {})]
      (is (false? (:ok p)))
      (is (str/includes? (:error p) "cannot parse"))))
  (testing "a config error is reported the way apply reports it, and plans nothing"
    (let [p (gui/plan-for-text "{:mcps {:broken {}}}" "/tmp/agentctl-gui/agents.edn" {})]
      (is (false? (:ok p)))
      (is (nil? (:plan p)))
      (is (some #(= "error" (:level %)) (:findings p))))))

(deftest gui-masks-a-credential-it-reads-out-of-a-tool-config
  (testing "the plan is rendered text precisely so display-side masking applies"
    (let [dir (temp-dir)
          proj (str dir "/proj")
          _ (fs/create-dirs proj)
          _ (u/write-json! (str proj "/.mcp.json")
                           {:mcpServers {:shared {:command "/bin/echo"
                                                  :env {:TOKEN "sk-live-guitest"}}}})
          text (str "{:mcps {:shared {:cmd \"/bin/echo hi\" :tools [:claude]}}\n"
                    " :projects {:proj {:path \"" proj "\" :executors {:claude {}} :mcp [:shared]}}}\n")
          p (gui/plan-for-text text (str dir "/agents.edn") {:tools #{:claude} :kinds #{} :projects #{}})]
      (is (not (str/includes? (pr-str p) "sk-live-guitest"))))))

(deftest gui-hands-out-nothing-without-this-runs-token
  (let [ctx {:tok "sekret" :path "/tmp/agentctl-gui/agents.edn" :base-opts {} :lock (Object.)}
        req (fn [m] (gui/handler ctx (merge {:request-method :get :uri "/"
                                             :headers {"host" "127.0.0.1:7777"}} m)))]
    (is (= 403 (:status (req {}))) "no token")
    (is (= 403 (:status (req {:headers {"host" "127.0.0.1:7777"} :query-string "t=wrong"}))))
    (is (= 200 (:status (req {:query-string "t=sekret"}))))
    (is (= 200 (:status (req {:headers {"host" "localhost:7777" "x-agentctl-token" "sekret"}}))))
    (testing "a page on another origin can reach 127.0.0.1 — but not under that name"
      (is (= 403 (:status (req {:headers {"host" "attacker.example"} :query-string "t=sekret"})))))
    (testing "the page carries the token so the first fetch is authorized"
      (is (str/includes? (:body (req {:query-string "t=sekret"})) "sekret")))))

(deftest gui-apply-is-gated-on-the-plan-the-user-was-shown
  (let [dir (temp-dir)
        path (str dir "/agents.edn")
        res (gui/apply-for-text! gui-config path
                                 {:tools #{:pi} :kinds #{} :projects #{}
                                  :expect "0 to add, 0 to change, 0 to remove, 0 unchanged"})]
    (is (= 409 (:status res)))
    (is (true? (:stale res)))
    (is (not (u/exists? path)) "a refused apply writes nothing")))

(deftest the-gui-table-says-the-same-thing-as-the-text
  ;; the GUI renders a table from `:ops`, so the grouping and the masking both
  ;; have to happen here — the browser must not re-derive either
  (let [file "/tmp/agentctl-gui/settings.json"
        ops [(plan/op {:action :update :tool :claude :kind :mcps :id :slack :target file
                       :diffs [{:key :SLACK_TOKEN :before nil
                                :after "xoxc-111111111111-222222222222"}]})
             (plan/op {:action :create :tool :claude :kind :skills :id :demo
                       :target "/tmp/agentctl-gui/skills/demo"
                       :category :fs :entry "folder" :fs-op "symlink"
                       :from "~/packs/demo"
                       :cmds [["ln" "-s" "~/packs/demo" "/tmp/agentctl-gui/skills/demo"]]})
             (plan/op {:action :noop :tool :claude :kind :projects :id :hooks
                       :summary "hooks have no project scope" :warn true})]
        [{:keys [tool groups]} :as data] (plan/plan-data ops {})
        by-lane (into {} (map (juxt :category identity)) groups)]
    (is (= 1 (count data)))
    (is (= "claude" tool))
    (is (= #{"struct" "fs" "report"} (set (keys by-lane))))
    (testing "a credential is masked here exactly as it is in the text"
      (is (not (str/includes? (pr-str data) "111111111111")))
      (is (str/includes? (pr-str data) "xoxc…22")))
    (testing "the fs lane carries what a file change is: which entry, from where"
      (let [g (by-lane "fs")]
        (is (= ["folder" "symlink" "~/packs/demo"] [(:entry g) (:fs-op g) (:from g)]))
        (is (= ["ln -s ~/packs/demo /tmp/agentctl-gui/skills/demo"] (:cmds g)))))
    (testing "a warning noop is kept: a setting that went nowhere is the report"
      (is (= "noop" (:action (by-lane "report")))))
    (testing "agentctl never renames or moves, so no lane claims it does"
      (is (every? #{"create" "update" "delete" "noop"} (map :action groups))))))

(deftest one-file-one-entry-in-the-table-too
  (let [file "/tmp/agentctl-gui/.claude.json"
        mk (fn [id v] (plan/op {:action :create :tool :claude :kind :mcps :id id :target file
                                :diffs [{:key :command :before nil :after v}]}))
        [{[g] :groups}] (plan/plan-data [(mk :a "/bin/a") (mk :b "/bin/b")] {})]
    (is (= "mcps/{a b}" (:label g)) "two servers in one file read as one entry")
    (is (= ["a" "b"] (:ids g)))
    (testing "a field is qualified by its server exactly where the text qualifies it:
              one field of an op's own naming reads clearly on its own, several do not"
      (is (= ["command" "command"] (mapv :key (:rows g)))))
    (let [mk2 (fn [id v] (plan/op {:action :create :tool :claude :kind :mcps :id id :target file
                                   :diffs [{:key :command :before nil :after v}
                                           {:key :type :before nil :after "stdio"}]}))
          [{[g2] :groups}] (plan/plan-data [(mk2 :a "/bin/a") (mk2 :b "/bin/b")] {})]
      (is (= ["a.command" "a.type" "b.command" "b.type"] (mapv :key (:rows g2)))))))


(deftest a-command-is-slots-and-the-argv-agrees-with-them
  ;; the run lane renders `clone` and `obra/superpowers` rather than one
  ;; 78-column line, so the slots must describe the argv that actually runs
  (let [r (plan/run {:program "gh" :action ["repo" "clone"]
                     :subject "obra/superpowers"
                     :into (str u/home "/.agents/skill-packs/superpowers")
                     :flags [["--branch" "main"]]
                     :argv ["gh" "repo" "clone" "obra/superpowers"
                            (str u/home "/.agents/skill-packs/superpowers")
                            "--" "--branch" "main"]})]
    (is (= "gh" (:program r)))
    (is (= "repo clone" (:action r)))
    (is (= "obra/superpowers" (:subject r)))
    (is (= "~/.agents/skill-packs/superpowers" (:into r)))
    (is (= [{:k "--branch" :v "main"}] (:flags r)))
    ;; the shell line is the argv, tilde'd — never a re-rendering of the slots
    (is (= "gh repo clone obra/superpowers ~/.agents/skill-packs/superpowers -- --branch main"
           (:shell r))))
  ;; a slot the argv does not carry is a command described as something it is
  ;; not, which is worse than printing no command at all
  (is (thrown? AssertionError
               (plan/run {:program "git" :action ["clone"] :subject "git@example:repo"
                          :into "/tmp/elsewhere"
                          :argv ["git" "clone" "git@example:repo" "/tmp/here"]}))))

(deftest the-fs-lane-carries-a-shell-equivalent-not-a-step
  ;; `link-op` runs through fs so it can back the old path up first; the ln
  ;; line is what a person would type, and the table must not claim otherwise
  (let [src (str u/home "/packs/demo/skills/wrap-up")
        dest (str u/home "/.claude/skills/wrap-up")
        _ (fs/create-dirs src)
        op (plan/link-op {:tool :claude :kind :skills :id :wrap-up :src src :dest dest})
        [{[g] :groups}] (plan/plan-data [op] {})]
    (is (= "fs" (:category g)))
    (is (empty? (:runs g)) "an fs op runs no command")
    (is (= 1 (count (:cmds g))))
    (is (str/starts-with? (first (:cmds g)) "ln -s "))))

(deftest global-wrap-up-removes-project-links
  (doseq [[tool planner subdir global-var]
          [[:claude claude/plan "/.claude/skills" #'claude/skills-dir]
           [:antigravity antigravity/plan "/.agents/skills" #'antigravity/skills-dir]]]
    (let [dir (temp-dir)
          src (str dir "/pack/skills/wrap-up")
          global-dir (str dir "/global")
          raw {:skill-packs {:kit {:uri (str "file://" dir "/pack")}}
               :skills {:wrap-up {:from :kit :tools [tool]}}
               :projects (into {} (for [id [:a :b :legacy :directory]]
                                    [id {:path (str dir "/" (name id))
                                         :executors #{tool} :skills [:wrap-up]}]))}
          _ (fs/create-dirs src)
          _ (spit (str src "/SKILL.md") "---\nname: wrap-up\n---\nWrap up the session.\n")]
      (with-redefs-fn {global-var global-dir}
        (fn []
          (let [local (config/normalize raw "test")
                local-ops (filter #(and (= :skills (:kind %))
                                       (#{:a :b} (:project %)))
                                  (planner local state/empty-state))
                _ (is (empty? (:failed (core/execute! local-ops))))
                st (core/sync-state! state/empty-state local)
                legacy (str dir "/legacy" subdir "/wrap-up")
                directory (str dir "/directory" subdir "/wrap-up")
                _ (fs/create-dirs (fs/parent legacy))
                _ (fs/create-sym-link legacy (str dir "/missing-source"))
                _ (fs/create-dirs directory)
                _ (spit (str directory "/SKILL.md") "local content")
                ;; Keep a direct reference, expand a pack, and drop a reference:
                ;; all three must shed the redundant project link.
                global (config/normalize (-> raw
                                             (assoc-in [:skills :wrap-up :scope] :global)
                                             (assoc-in [:projects :b :skills] [:kit])
                                             (assoc-in [:projects :legacy :skills] [])) "test")
                ops (vec (filter #(= :skills (:kind %)) (planner global st)))
                deletes (filter #(= :delete (:action %)) ops)
                out (plan/render-plan ops {})]
            (is (= #{:a/wrap-up :b/wrap-up :legacy/wrap-up} (set (map :id deletes))))
            (is (= 1 (count (filter #(= :create (:action %)) ops))))
            (doseq [id [:a :b :legacy]]
              (is (str/includes? out (str "- projects/" (name id) " skills/")))
              (is (fs/sym-link? (str dir "/" (name id) subdir "/wrap-up"))))
            (is (not (u/exists? (str global-dir "/wrap-up"))) "dry plan writes nothing")
            ;; A project-filtered run cannot remove the only working install.
            (is (= 3 (count (:failed (core/execute! deletes)))))
            (is (empty? (:failed (core/execute! ops))))
            (doseq [o deletes] (is (not (u/exists? (:target o)))))
            (is (= "local content" (slurp (str directory "/SKILL.md"))))
            (is (fs/sym-link? (str global-dir "/wrap-up")))
            (is (u/exists? (str src "/SKILL.md")) "unlink preserves the source")
            (let [next-state (core/sync-state! st global)
                  inv (set (core/inventory global))]
              (is (contains? inv [tool :skills :wrap-up]))
              (is (not-any? #(and (= :skills (second %)) (namespace (nth % 2))) inv))
              (is (empty? (filter #(and (= :skills (:kind %)) (plan/mutating? %))
                                  (planner global next-state)))))))))))

(let [{:keys [fail error]} (run-tests 'agentctl-test)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
