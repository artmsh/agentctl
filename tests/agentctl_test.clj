(ns agentctl-test
  (:require [agentctl.adapters.common :as common]
            [agentctl.config :as config]
            [agentctl.core :as core]
            [agentctl.imports :as imports]
            [agentctl.plan :as plan]
            [agentctl.refs :as refs]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.toml :as toml]
            [agentctl.util :as u]
            [agentctl.validate :as validate]
            [babashka.fs :as fs]
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

(deftest let-bindings-substitute-at-root
  (let [{:keys [raw findings]}
        (config/expand-lets '{:#let {effort "high" who :claude}
                              :executors {:claude {:thinking $effort :model $who}}
                              :extra-providers {:omni {:key $PROVIDER_API_KEY}}})]
    (is (empty? findings))
    (is (= {:thinking "high" :model :claude} (get-in raw [:executors :claude])))
    (testing "an unbound SHOUTED $NAME stays an env reference"
      (is (= :env (:ref/kind (refs/parse (get-in raw [:extra-providers :omni :key]))))))
    (testing ":#let itself is consumed"
      (is (not (contains? raw :#let))))))

(deftest let-bindings-interpolate-into-strings
  (let [{:keys [raw]} (config/expand-lets '{:#let {ws "~/projects"}
                                            :mcps {:x {:cmd "bun run $ws/a/index.ts"}}})]
    (is (= "bun run ~/projects/a/index.ts" (get-in raw [:mcps :x :cmd])))
    (testing "an unbound $NAME in a string is left for env expansion"
      (is (= "$HOME/y" (:cmd (:y (:mcps (:raw (config/expand-lets
                                                '{:#let {ws "~/projects"}
                                                  :mcps {:y {:cmd "$HOME/y"}}}))))))))))

(deftest let-is-root-only
  (let [{:keys [findings]} (config/expand-lets '{:#let {a "1"}
                                                 :projects {:p {:#let {b "2"} :trusted true}}})]
    (is (= [:error] (map :level findings)))
    (is (str/includes? (:message (first findings)) "top level")))
  (testing "a binding referencing another binding is an error, not an ordering puzzle"
    (is (= [:error] (map :level (:findings (config/expand-lets '{:#let {a "1" b $a}})))))))

(deftest unbound-lowercase-ref-warns
  (let [{:keys [findings]} (config/expand-lets '{:#let {effort "high"}
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

(deftest structural-checks-catch-dangling-references
  (let [cfg (config/normalize sample-config "/tmp/agents.edn")
        msgs (map :message (config/structural-findings cfg))]
    (is (some #(str/includes? % "undefined pack") msgs))
    (is (some #(str/includes? % "stdio MCP server needs :command") msgs))
    (is (some #(str/includes? % "undefined mcp :missing") msgs))))

(deftest capability-table
  (is (config/supports? :codex :mcps))
  (is (not (config/supports? :unknown :mcps)))
  (is (= [:claude :codex :pi :omp] (config/tools-for :skills))))

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
      (let [cfg2 (assoc-in cfg [:skill-packs :superpowers :root] (str dir "/not-cloned"))]
        (is (= [:superpowers] (:pending (sources/project-skills cfg2 proj))))))))

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
        ops (core/build-plan cfg state/empty-state {})
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

(let [{:keys [fail error]} (run-tests 'agentctl-test)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
