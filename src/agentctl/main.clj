(ns agentctl.main
  "CLI entrypoint: apply / apply! / validate / import / import! / plan / state."
  (:require [agentctl.config :as config]
            [agentctl.core :as core]
            [agentctl.gui :as gui]
            [agentctl.hosts :as hosts]
            [agentctl.imports :as imports]
            [agentctl.plan :as plan]
            [agentctl.remote :as remote]
            [agentctl.state :as state]
            [agentctl.statusline :as statusline]
            [agentctl.util :as u]
            [agentctl.validate :as validate]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:refer-clojure :exclude [import]))

(defn- wrap-words
  "`words` as lines of at most `width` characters, each starting with `indent`."
  [indent width words]
  (map #(str indent (str/join " " %))
       (reduce (fn [lines w]
                 (let [line (peek lines)]
                   (if (and (seq line) (> (+ (count indent) (count (str/join " " (conj line w)))) width))
                     (conj lines [w])
                     (conj (pop lines) (conj line w)))))
               [[]] words)))

(def usage
  (str/join
   "\n"
   (concat
   ["agentctl — declarative provisioning for coding agents (claude, codex, pi, omp, llm, antigravity, kimi, dsh)"
    ""
    "USAGE"
    "  agentctl <command> [options]"
    ""
    "COMMANDS"
    "  apply            Show what would change. Read-only. Exit 2 when drift exists."
    "  apply!           Converge the environment onto agents.edn."
    "  validate         Check the config and the environment; print a report."
    "  import           Show the agents.edn that the current environment implies."
    "  import!          Write that agents.edn (existing file is backed up first)."
    "  state            Show what agentctl currently owns."
    "  gui              Edit agents.edn in a browser beside its live dry run."
    ""
    "OPTIONS"
    "  -f, --file PATH      Config file (default ~/.config/agents.edn)"
    "  -t, --tool TOOL      Restrict to a tool (repeatable): claude codex pi omp llm antigravity kimi dsh"
    "  -k, --kind KIND      Restrict to a resource kind (repeatable):"]
   ;; the DSL's kinds, then the old format's own, each named against the DSL
   ;; kind it stands in for — never a list of its own beside `core/kinds-for`
   (let [dsl (core/kinds-for {:entity-dsl? true})
         old (core/kinds-for {})]
     (concat
      (wrap-words "                       " 92 (map name dsl))
      ;; the DSL-only kind, and the others a project's `projects` op is
      ;; refiled under in the DSL — all from `core/dsl-refiled`
      (let [dsl-only (remove (set old) dsl)
            also (sort (remove (set dsl-only) (mapcat val core/dsl-refiled)))]
        (wrap-words "                       " 92
                    (str/split (str "legacy (old-format configs only, in place of "
                                    (str/join ", " (map name dsl-only))
                                    (when (seq also)
                                      (str ", and of a project's " (str/join " and " (map name also))))
                                    "): " (str/join " " (map name (remove (set dsl) old))))
                               #" ")))))
   ["  -p, --project PATH   Restrict to a HOME-relative path or tree (repeatable); selects"
    "                       everything that project owns, whatever its kind"
    "      --only KIND/ID   apply/apply!: plan one entity wherever it is placed (repeatable),"
    "                       e.g. --only skills/review; nothing outside it is pruned"
    "      --user           apply/apply!: plan user scope only, no project; prunes only"
    "                       user-scope resources dropped from the config"
    "  -A, --all            apply/apply!: ignore the working directory and plan the"
    "                       whole config (see SCOPE)"
    "      --host NAME      apply/apply!/validate/state: act on a host declared under"
    "                       :hosts, over SSH, instead of on this machine"
    "      --json           Machine-readable output"
    "  -v, --verbose        Show per-field diffs and target paths"
    "      --show-noop      Include unchanged / informational entries"
    "      --deep           validate: also probe the network (provider URLs, git remotes)"
    "      --replace        import!: overwrite instead of merging into the existing file"
    "  -y, --yes            apply!: do not prompt before mutating"
    "      --port N         gui: listen on this port (default: any free one)"
    "      --no-open        gui: do not open a browser"
    "      --debug          On an error, also print its stack trace to stderr"
    "                       (so does AGENTCTL_DEBUG=1)"
    "  -h, --help           This message"
    ""
    "SCOPE"
    "  apply and apply! read the working directory. Inside a resolved project they"
    "  plan only that project; on its workspace directory, only projects beneath it."
    "  Explicit -p selects a path or tree. --all disables working-directory narrowing,"
    "  and so do --only and --user, which say what to plan by hand. Elsewhere the"
    "  whole config is planned. A narrowed run (-t -k -p --only --user, or the"
    "  working directory) forgets ownership only of what it removed itself."])))

(defn parse-args [args]
  (loop [args args opts {:tools #{} :kinds #{} :projects #{}}]
    (if-let [a (first args)]
      (case a
        ("-f" "--file") (recur (drop 2 args) (assoc opts :file (second args)))
        ("-t" "--tool") (recur (drop 2 args) (update opts :tools conj (keyword (second args))))
        ("-k" "--kind") (recur (drop 2 args) (update opts :kinds conj (keyword (second args))))
        ("-p" "--project") (recur (drop 2 args) (update opts :projects conj (keyword (second args))))
        "--only" (recur (drop 2 args) (update opts :only (fnil conj []) (str (second args))))
        "--user" (recur (rest args) (assoc opts :user true))
        "--json" (recur (rest args) (assoc opts :json true))
        ("-v" "--verbose") (recur (rest args) (assoc opts :verbose true))
        "--show-noop" (recur (rest args) (assoc opts :show-noop true))
        "--deep" (recur (rest args) (assoc opts :deep true))
        "--replace" (recur (rest args) (assoc opts :replace true))
        ("-y" "--yes") (recur (rest args) (assoc opts :yes true))
        ("-A" "--all") (recur (rest args) (assoc opts :all true))
        "--host" (recur (drop 2 args) (assoc opts :host (keyword (second args))))
        "--port" (recur (drop 2 args) (assoc opts :port (str (second args))))
        "--no-open" (recur (rest args) (assoc opts :no-open true))
        "--debug" (recur (rest args) (assoc opts :debug true))
        ("-h" "--help") (recur (rest args) (assoc opts :help true))
        (cond
          ;; a mistyped flag must not fall through as an argument: `--onyl x`
          ;; would otherwise plan the whole config, prunes included
          (and (str/starts-with? a "-") (not= a "-"))
          (recur (rest args) (update opts :unknown (fnil conj []) a))
          (:command opts) (recur (rest args) (update opts :rest (fnil conj []) a))
          :else (recur (rest args) (assoc opts :command a))))
      opts)))

(defn- op->data [o]
  (-> o (dissoc :exec!) (update :tool u/kw->str) (update :kind u/kw->str) (update :id u/kw->str)))

(defn- load! [opts]
  (config/load-config (or (:file opts) config/default-path)))

;; ---------------------------------------------------------------- commands

(defn- scope-note
  "One line saying the working directory narrowed this run, and how to opt out.
   Silence here would be the worst of it: `apply` exiting 0 looks like a clean
   machine, not like a run that never looked at most of the config."
  [cfg {:keys [where projects path]}]
  ;; a DSL target id is a path digest; its path is the name a person knows
  (let [label #(or (when (:entity-dsl? cfg) (some-> (get-in cfg [:projects % :path]) u/tilde))
                   (u/kw->str %))]
  (str "scope: "
       (case where
         :project (str "project " (label (first projects)) " — this directory is inside it")
         :root (str (str/join ", " (sort (map label projects)))
                    " — the projects under " (u/tilde path)))
       " (--all for the whole config)")))

(defn- finding->data
  "A finding as `validate --json` prints one."
  [f]
  {:level (name (:level f)) :where (mapv u/kw->str (:where f)) :message (:message f)})

(defn- check-config!
  "Refuse a config with errors; say what is merely odd about one without, and
   return those findings. `findings` are the config's structural ones unless
   given: a local run also checks what this machine says, once it knows its
   scope. Under --json nothing is printed beside the document: the refusal is
   a document of its own, and the warnings are the caller's to put in
   `findings` — a line of text above the JSON is output no parser reads."
  [cfg opts & [findings]]
  (let [findings (or findings (config/structural-findings cfg))
        errors (filter #(= :error (:level %)) findings)
        warnings (filter #(= :warn (:level %)) findings)]
    (when (seq errors)
      (if (:json opts)
        (println (json/generate-string {:error "config errors — refusing to plan"
                                        :findings (mapv finding->data (concat errors warnings))}
                                       {:pretty true}))
        (do (println "config errors — refusing to plan:")
            (doseq [f errors] (println "  ✗" (str/join " " (map u/kw->str (:where f))) "—" (:message f)))))
      (System/exit 1))
    (when-not (:json opts)
      (doseq [f warnings]
        (println "  ⚠" (str/join " " (map u/kw->str (:where f))) "—" (:message f))))
    warnings))

(defn- check-kinds!
  "A `-k` or `--only` kind is checked against every kind before the config is
   read; one this config's format never plans would narrow the run to
   nothing. Refused when the format is known; returned as warnings when the
   config does not decide one yet."
  [cfg opts]
  (let [{errors :error warns :warn} (group-by #(nth % 3) (core/dead-selectors cfg opts))]
    (when-let [[flag v msg] (first errors)]
      (throw (ex-info (str flag " " v ": " msg) {})))
    (vec (for [[flag v msg] warns] (str flag " " v " — " msg)))))

(defn- applied->data
  "What a mutating run did, for --json: each op done, and each failed with
   its error."
  [done failed]
  {:done (mapv #(select-keys (op->data %) [:tool :kind :id :action]) done)
   :failed (mapv #(select-keys (op->data %) [:tool :kind :id :action :error]) failed)})

(defn- print-json [doc] (println (json/generate-string doc {:pretty true})))

(defn- confirm!
  "Ask before a mutating run, unless told not to. Declining exits. Under
   --json `-y` is required, so there is nothing to ask and nothing to print:
   the document carries `secrets`."
  [opts changes]
  (let [secretive (filter #(= :secret (:risk %)) changes)]
    (when (and (seq secretive) (not (:json opts)))
      (println (format "\n⚠ %d change(s) write resolved secrets into tool config files."
                       (count secretive))))
    (when-not (:yes opts)
      (print (format "\napply %d change(s)? [y/N] " (count changes)))
      (flush)
      (let [answer (str/lower-case (str/trim (or (read-line) "")))]
        (when-not (contains? #{"y" "yes"} answer)
          (println "aborted")
          (System/exit 1))))))

(defn cmd-apply-host
  "`apply`/`apply!` for one declared host. The working directory says nothing
   about another machine and `-p` has no project there to select, so neither
   scopes this. An unreachable host raises out of here and exits 1: it can be
   neither of the answers 0 (no drift) and 2 (drift) would give."
  [opts mutate?]
  (when (seq (:projects opts))
    (throw (ex-info "--project does not apply with --host: hosts have no projects yet" {})))
  (when (seq (:only opts))
    (throw (ex-info "--only does not apply with --host: a host is planned as a whole" {})))
  (let [cfg (load! opts)
        findings (check-config! cfg opts)
        ;; the config's format holds for a host as it does here: a kind it
        ;; never plans is refused before the host is looked up or reached
        warnings (check-kinds! cfg opts)
        host (hosts/lookup cfg (:host opts))
        st (state/load-host-state (:id host))
        planned (hosts/plan-host host st opts)
        ops (:ops planned)
        changes (filter plan/mutating? ops)
        ;; under --json, printed once, when the run is over: apply! adds
        ;; what it applied, so stdout stays one document
        doc {:host (name (:id host))
             :ops (map op->data ops)
             :skipped (map name (:skipped planned))
             :warnings warnings
             :findings (mapv finding->data findings)
             :summary (plan/summary-line ops)}]
    (when-not (:json opts)
      (doseq [w warnings] (println (str "  ⚠ " w)))
      (println (str "host: " (name (:id host)) " (" (:ssh host) ", " (name (:os host)) ")"))
      (when-let [missing (seq (:skipped planned))]
        (println (str "skipped (CLI not installed on host): " (str/join ", " (map name missing)))))
      (if (plan/show-plan? ops opts)
        (println (plan/render-plan ops opts))
        (println "\nno changes — host matches agents.edn"))
      (println)
      (println (plan/summary-line ops)))
    (cond
      (not mutate?)
      (do (when (:json opts) (print-json doc))
          (System/exit (if (seq changes) 2 0)))

      (empty? changes)
      (do (state/save-host! (:id host) (hosts/sync-host-state st planned [] []))
          (when (:json opts) (print-json (assoc doc :applied (applied->data [] []))))
          (System/exit 0))

      :else
      (do
        (confirm! opts changes)
        (let [{:keys [plan done failed]} (hosts/converge! cfg host st opts planned)
              backups (remote/backup-dir host (get-in plan [:inventory :home]))]
          (state/save-host! (:id host) (hosts/sync-host-state st plan done failed))
          (if (:json opts)
            (print-json (assoc doc :applied (applied->data done failed) :backups backups))
            (do
              (println (format "\napplied %d change(s)%s"
                               (count done)
                               (if (seq failed) (str ", " (count failed) " failed") "")))
              (doseq [f failed]
                (println "  ✗" (name (:tool f)) (name (:kind f)) (u/kw->str (:id f)) "—" (:error f)))
              (println (str "backups on host: " backups))))
          (System/exit (if (seq failed) 1 0)))))))

(defn cmd-apply [opts mutate?]
  (let [cfg (load! opts)
        st (state/load-state)
        findings (check-config! cfg opts)
        kind-warnings (check-kinds! cfg opts)]
    (let [;; where the run happens is itself a scope: `apply` in a project asks
          ;; about that project, not about the machine. An explicit -p is the
          ;; same question asked by hand, and --all is the way to say the whole
          ;; config on purpose — either one outranks the directory, and so do
          ;; --only and --user, which name what to plan by hand too.
          scope (when-not (or (:all opts) (:user opts) (seq (:only opts)) (seq (:projects opts)))
                  (core/cwd-scope cfg (System/getProperty "user.dir")))
          opts (cond-> opts scope (assoc :projects (:projects scope)))
          ;; this machine's verdict on the status-line file; a run that
          ;; cannot write the file gets it as a warning
          findings (concat findings (check-config! cfg opts (vec (statusline/findings cfg opts))))
          ops (core/build-plan cfg st opts)
          cfg (assoc cfg :planned-ops ops)
          changes (filter plan/mutating? ops)
          ;; a selector that names nothing narrows the run to nothing, which
          ;; reads as converged — in either output, so --json carries them too
          warnings (concat
                    kind-warnings
                    (for [p (core/unmatched-projects cfg opts)]
                      (str "-p " p " — no declared project there"))
                    (for [o (core/unmatched-only cfg st opts)]
                      (str "--only " o " — nothing declared or owned by that id")))
          ;; under --json, printed once, when the run is over: apply! adds
          ;; what it applied, so stdout stays one document
          doc (cond-> {:ops (map op->data ops)
                       :warnings (vec warnings)
                       :findings (mapv finding->data findings)
                       :secrets (count (filter #(= :secret (:risk %)) changes))
                       :summary (plan/summary-line ops)}
                scope (assoc :scope {:where (u/kw->str (:where scope))
                                     :path (:path scope)
                                     :projects (mapv u/kw->str (sort (:projects scope)))}))]
      (when-not (:json opts)
        (when scope (println (scope-note cfg scope)))
        (doseq [w warnings] (println (str "  ⚠ " w)))
        (when-let [missing (seq (core/missing-tools cfg opts))]
          (println (str "skipped (CLI not installed): " (str/join ", " (map name missing)))))
        (if (plan/show-plan? ops opts)
          (println (plan/render-plan ops opts))
          (println "\nno changes — environment matches agents.edn"))
        (println)
        (println (plan/summary-line ops)))
      (cond
        (not mutate?)
        (do (when (:json opts) (print-json doc))
            (System/exit (if (seq changes) 2 0)))

        (empty? changes)
        (do (state/save! (core/sync-state! st cfg #{} (when (core/scoped? opts) #{})))
            (when (:json opts) (print-json (assoc doc :applied (applied->data [] []))))
            (System/exit 0))

        :else
        (do
          (confirm! opts changes)
          (let [{:keys [done failed]} (core/converge! cfg st opts ops)]
            ;; never claim ownership of a resource whose creation failed; on a
            ;; scoped run (-t/-k/-p/--only/--user) a data-carrying id (hooks) only gets its
            ;; value overwritten if this run actually wrote it — see
            ;; `core/sync-state!`
            (state/save! (core/sync-state! st cfg
                                            (set (mapcat core/op-keys failed))
                                            (when (core/scoped? opts)
                                              (set (mapcat core/op-keys done)))))
            (if (:json opts)
              (print-json (assoc doc :applied (applied->data done failed)
                                 :backups (u/tilde (u/backup-dir))))
              (do
                (println (format "\napplied %d change(s)%s"
                                 (count done)
                                 (if (seq failed) (str ", " (count failed) " failed") "")))
                (doseq [f failed]
                  (println "  ✗" (name (:tool f)) (name (:kind f)) (u/kw->str (:id f)) "—" (:error f)))
                (println (str "backups: " (u/tilde (u/backup-dir))))))
            (System/exit (if (seq failed) 1 0))))))))

(defn cmd-validate [opts]
  (let [cfg (load! opts)
        report (if (:host opts) (validate/run-host cfg opts) (validate/run cfg opts))]
    (if (:json opts)
      (println (json/generate-string report {:pretty true}))
      (validate/print-report report))
    (System/exit (if (pos? (:errors report)) 1 0))))

(defn cmd-import [opts write?]
  (let [existing (when (u/exists? (or (:file opts) config/default-path))
                   (u/read-edn (or (:file opts) config/default-path)))
        {:keys [config notes]} (imports/scan {:existing (when-not (:replace opts) existing)})
        target (or (:file opts) config/default-path)
        text (imports/render config)]
    (if (:json opts)
      (println (json/generate-string {:config (u/norm config) :notes notes} {:pretty true}))
      (do
        (doseq [n notes] (println ";;" n))
        (println text)))
    (when write?
      (when existing
        (let [b (u/backup! target)]
          (println (str "\nbacked up " (u/tilde target) " -> " (u/tilde b)))))
      (u/write-text! target text)
      (println (str "wrote " (u/tilde target))))
    (System/exit 0)))

(defn cmd-state [opts]
  (let [st (if-let [h (:host opts)] (state/load-host-state h) (state/load-state))]
    (if (:json opts)
      (println (json/generate-string (u/norm (:managed st)) {:pretty true}))
      (do
        (println (str "state: " (u/tilde (if-let [h (:host opts)] (state/host-path h) state/path))))
        (doseq [[[tool kind id] _] (sort-by (comp str key) (:managed st))]
          (println (format "  %-8s %-11s %s" (name tool) (name kind) id)))
        (println (format "\n%d managed resource(s)" (count (:managed st))))))
    (System/exit 0)))

(defn cmd-gui [opts]
  (let [port (some-> (:port opts) str parse-long)]
    (when (and (:port opts) (nil? port))
      (println (str "--port wants a number, not " (pr-str (:port opts))))
      (System/exit 1))
    (gui/run! (assoc opts
                     :port (or port 0)
                     :path (or (:file opts) config/default-path)
                     :open? (not (:no-open opts))))))

(defn- check-selectors!
  "`-t`, `-k` and `--only` each name something to plan, and a name nothing
   answers to selects nothing — a typo would read as a converged machine.
   Returns `opts` with each `--only` parsed into `[kind id]`."
  [{:keys [only] :as opts}]
  (when-let [[flag v msg] (first (core/unknown-selectors opts))]
    (throw (ex-info (str flag " " (u/kw->str v) ": " msg) {})))
  (when (and (:user opts) (seq (:projects opts)))
    (throw (ex-info "--user plans no project; it does not combine with --project" {})))
  (cond-> opts
    (seq only)
    (assoc :only (vec (for [s only
                            :let [[_ k id] (re-matches #"([^/]+)/(.+)" s)
                                  kind (some-> k keyword)]]
                        (cond
                          (nil? k) (throw (ex-info (str "--only " s ": want KIND/ID, e.g. --only skills/review") {}))
                          (not (some #{kind} core/kinds))
                          (throw (ex-info (str "--only " s ": " (core/kind-problem kind)) {}))
                          ;; both filters apply, so this one could select nothing
                          (not (core/kind-selected? opts kind))
                          (throw (ex-info (str "--only " s ": -k " (str/join " -k " (map name (sort (:kinds opts))))
                                               " leaves no " (name kind) " to select") {}))
                          :else [kind id]))))))

(defn- debug?
  "`--debug`, or `AGENTCTL_DEBUG` set to anything but empty, 0 or false."
  [opts]
  (or (:debug opts)
      (not (contains? #{nil "" "0" "false"} (System/getenv "AGENTCTL_DEBUG")))))

(def ^:private commands
  #{"apply" "plan" "apply!" "validate" "import" "import!" "state" "gui"})

(defn -main [& args]
  (let [{:keys [command help] :as opts} (parse-args args)]
    (cond
      (or help (nil? command)) (println usage)
      ;; what the command is comes before what its options may be: a typo in
      ;; the command must not be reported as an option it does not take
      (not (commands command))
      (do (if (:json opts)
            (println (json/generate-string {:error (str "unknown command: " command)} {:pretty true}))
            (do (println (str "unknown command: " command "\n"))
                (println usage)))
          (System/exit 1))
      :else
      (try
        (when-let [[flag] (:unknown opts)]
          (throw (ex-info (str "unknown option " flag " — agentctl --help lists the options") {})))
        (when (and (:host opts) (not (#{"apply" "plan" "apply!" "validate" "state"} command)))
          (throw (ex-info (str command " does not take --host") {})))
        (when (and (or (seq (:only opts)) (:user opts)) (not (#{"apply" "plan" "apply!"} command)))
          (throw (ex-info (str command " does not take --only or --user") {})))
        ;; stdout is the JSON document; a confirmation prompt has nowhere to go
        (when (and (= "apply!" command) (:json opts) (not (:yes opts)))
          (throw (ex-info "apply! --json needs -y: a confirmation prompt cannot share stdout with JSON" {})))
        (let [opts (check-selectors! opts)]
          (case command
            "apply" (if (:host opts) (cmd-apply-host opts false) (cmd-apply opts false))
            "plan" (if (:host opts) (cmd-apply-host opts false) (cmd-apply opts false))
            "apply!" (if (:host opts) (cmd-apply-host opts true) (cmd-apply opts true))
            "validate" (cmd-validate opts)
            "import" (cmd-import opts false)
            "import!" (cmd-import opts true)
            "state" (cmd-state opts)
            "gui" (cmd-gui opts)))
        ;; not only agentctl's own refusals: a config that cannot be read (a
        ;; directory, no permission) throws from the JVM, and a stack trace on
        ;; stderr with nothing on stdout is no answer, least of all under
        ;; --json. A script reading --json gets its error as JSON too.
        (catch Exception e
          (let [msg (or (ex-message e) (.getName (class e)))]
            (println (if (:json opts)
                       (json/generate-string {:error msg} {:pretty true})
                       (str "error: " msg))))
          ;; the one line is the answer; the trace is for whoever has to
          ;; find out why, and goes where it cannot break stdout
          (when (debug? opts) (.printStackTrace e))
          (System/exit 1))))))
