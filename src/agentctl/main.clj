(ns agentctl.main
  "CLI entrypoint: apply / apply! / validate / import / import! / plan / state."
  (:require [agentctl.config :as config]
            [agentctl.core :as core]
            [agentctl.gui :as gui]
            [agentctl.imports :as imports]
            [agentctl.plan :as plan]
            [agentctl.state :as state]
            [agentctl.util :as u]
            [agentctl.validate :as validate]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:refer-clojure :exclude [import]))

(def usage
  (str/join
   "\n"
   ["agentctl — declarative provisioning for coding agents (claude, codex, pi, omp, llm, antigravity)"
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
    "  -t, --tool TOOL      Restrict to a tool (repeatable): claude codex pi omp llm antigravity"
    "  -k, --kind KIND      Restrict to a resource kind (repeatable):"
    "                       settings mcps skills providers memory projects skill-packs"
    "  -p, --project ID     Restrict to one declared project (repeatable); selects"
    "                       everything that project owns, whatever its kind"
    "  -A, --all            apply/apply!: ignore the working directory and plan the"
    "                       whole config (see SCOPE)"
    "      --json           Machine-readable output"
    "  -v, --verbose        Show per-field diffs and target paths"
    "      --show-noop      Include unchanged / informational entries"
    "      --deep           validate: also probe the network (provider URLs, git remotes)"
    "      --replace        import!: overwrite instead of merging into the existing file"
    "  -y, --yes            apply!: do not prompt before mutating"
    "      --port N         gui: listen on this port (default: any free one)"
    "      --no-open        gui: do not open a browser"
    "  -h, --help           This message"
    ""
    "SCOPE"
    "  apply and apply! read the working directory. Inside a declared project they"
    "  plan only that project; on the directory the declared projects sit under,"
    "  only those projects. Anywhere else — and with -p or --all — nothing is"
    "  narrowed and the whole config is planned."]))

(defn parse-args [args]
  (loop [args args opts {:tools #{} :kinds #{} :projects #{}}]
    (if-let [a (first args)]
      (case a
        ("-f" "--file") (recur (drop 2 args) (assoc opts :file (second args)))
        ("-t" "--tool") (recur (drop 2 args) (update opts :tools conj (keyword (second args))))
        ("-k" "--kind") (recur (drop 2 args) (update opts :kinds conj (keyword (second args))))
        ("-p" "--project") (recur (drop 2 args) (update opts :projects conj (keyword (second args))))
        "--json" (recur (rest args) (assoc opts :json true))
        ("-v" "--verbose") (recur (rest args) (assoc opts :verbose true))
        "--show-noop" (recur (rest args) (assoc opts :show-noop true))
        "--deep" (recur (rest args) (assoc opts :deep true))
        "--replace" (recur (rest args) (assoc opts :replace true))
        ("-y" "--yes") (recur (rest args) (assoc opts :yes true))
        ("-A" "--all") (recur (rest args) (assoc opts :all true))
        "--port" (recur (drop 2 args) (assoc opts :port (str (second args))))
        "--no-open" (recur (rest args) (assoc opts :no-open true))
        ("-h" "--help") (recur (rest args) (assoc opts :help true))
        (if (:command opts)
          (recur (rest args) (update opts :rest (fnil conj []) a))
          (recur (rest args) (assoc opts :command a))))
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
  [{:keys [where projects path]}]
  (str "scope: "
       (case where
         :project (str "project " (u/kw->str (first projects)) " — this directory is inside it")
         :root (str (str/join ", " (sort (map u/kw->str projects)))
                    " — the projects under " (u/tilde path)))
       " (--all for the whole config)"))

(defn cmd-apply [opts mutate?]
  (let [cfg (load! opts)
        st (state/load-state)
        findings (config/structural-findings cfg)
        errors (filter #(= :error (:level %)) findings)]
    (when (seq errors)
      (println "config errors — refusing to plan:")
      (doseq [f errors] (println "  ✗" (str/join " " (map u/kw->str (:where f))) "—" (:message f)))
      (System/exit 1))
    (doseq [f findings :when (= :warn (:level f))]
      (println "  ⚠" (str/join " " (map u/kw->str (:where f))) "—" (:message f)))
    (let [;; where the run happens is itself a scope: `apply` in a project asks
          ;; about that project, not about the machine. An explicit -p is the
          ;; same question asked by hand, and --all is the way to say the whole
          ;; config on purpose — either one outranks the directory.
          scope (when-not (or (:all opts) (seq (:projects opts)))
                  (core/cwd-scope cfg (System/getProperty "user.dir")))
          opts (cond-> opts scope (assoc :projects (:projects scope)))
          ops (core/build-plan cfg st opts)
          changes (filter plan/mutating? ops)]
      (if (:json opts)
        (println (json/generate-string
                  (cond-> {:ops (map op->data ops)
                           :summary (plan/summary-line ops)}
                    scope (assoc :scope {:where (u/kw->str (:where scope))
                                         :path (:path scope)
                                         :projects (mapv u/kw->str (sort (:projects scope)))}))
                  {:pretty true}))
        (do
          (when scope (println (scope-note scope)))
          (when-let [missing (seq (core/missing-tools cfg opts))]
            (println (str "skipped (CLI not installed): " (str/join ", " (map name missing)))))
          (if (seq changes)
            (println (plan/render-plan ops opts))
            (println "\nno changes — environment matches agents.edn"))
          (println)
          (println (plan/summary-line ops))))
      (cond
        (not mutate?)
        (System/exit (if (seq changes) 2 0))

        (empty? changes)
        (do (state/save! (core/sync-state! st cfg #{} (when (core/scoped? opts) #{})))
            (System/exit 0))

        :else
        (let [secretive (filter #(= :secret (:risk %)) changes)]
          (when (seq secretive)
            (println (format "\n⚠ %d change(s) write resolved secrets into tool config files."
                             (count secretive))))
          (when-not (:yes opts)
            (print (format "\napply %d change(s)? [y/N] " (count changes)))
            (flush)
            (let [answer (str/lower-case (str/trim (or (read-line) "")))]
              (when-not (contains? #{"y" "yes"} answer)
                (println "aborted")
                (System/exit 1))))
          (let [{:keys [done failed]} (core/converge! cfg st opts ops)]
            ;; never claim ownership of a resource whose creation failed; on a
            ;; scoped run (-t/-k/-p) a data-carrying id (hooks) only gets its
            ;; value overwritten if this run actually wrote it — see
            ;; `core/sync-state!`
            (state/save! (core/sync-state! st cfg
                                            (set (map (juxt :tool :kind :id) failed))
                                            (when (core/scoped? opts)
                                              (set (map (juxt :tool :kind :id) done)))))
            (println (format "\napplied %d change(s)%s"
                             (count done)
                             (if (seq failed) (str ", " (count failed) " failed") "")))
            (doseq [f failed]
              (println "  ✗" (name (:tool f)) (name (:kind f)) (u/kw->str (:id f)) "—" (:error f)))
            (println (str "backups: " (u/tilde (u/backup-dir))))
            (System/exit (if (seq failed) 1 0))))))))

(defn cmd-validate [opts]
  (let [cfg (load! opts)
        report (validate/run cfg opts)]
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
  (let [st (state/load-state)]
    (if (:json opts)
      (println (json/generate-string (u/norm (:managed st)) {:pretty true}))
      (do
        (println (str "state: " (u/tilde state/path)))
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

(defn -main [& args]
  (let [{:keys [command help] :as opts} (parse-args args)]
    (cond
      (or help (nil? command)) (println usage)
      :else
      (try
        (case command
          "apply" (cmd-apply opts false)
          "plan" (cmd-apply opts false)
          "apply!" (cmd-apply opts true)
          "validate" (cmd-validate opts)
          "import" (cmd-import opts false)
          "import!" (cmd-import opts true)
          "state" (cmd-state opts)
          "gui" (cmd-gui opts)
          (do (println (str "unknown command: " command "\n"))
              (println usage)
              (System/exit 1)))
        (catch clojure.lang.ExceptionInfo e
          (println (str "error: " (ex-message e)))
          (System/exit 1))))))
