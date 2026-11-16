(ns agentctl.codex-schema
  "What codex-cli accepts in the nested settings agentctl writes for it, as
   probed against codex-cli 0.169.0 (a sandbox `CODEX_HOME`, a wrong-typed
   value per field, and the TUI's own pickers). Codex loads an unknown field
   or item without a word, so these checks are the only place a typo shows.

   Pure data and findings: `config` reports them before any plan, and the
   adapter refuses to plan what they reject."
  (:require [agentctl.util :as u]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- presets

(def preset-fields
  "`[tui.presets.<0-9>]`: `input` is what Codex submits, as typed — a prompt
   or a slash command such as `/model`; `label` names it in the browser."
  #{:input :label})

(defn preset-slot
  "The slot a declared key names, as the string Codex keys it by, or nil.
   Codex rejects anything but one digit: `slot must be a single digit from 0 to 9`."
  [k]
  (let [s (cond (integer? k) (str k) (or (keyword? k) (string? k)) (u/key-str k) :else nil)]
    (when (and s (re-matches #"[0-9]" s)) s)))

(defn- preset-findings [presets where]
  (if-not (map? presets)
    [[:error where ":presets maps a slot 0-9 to {:input \"…\" :label \"…\"}"]]
    (for [[k p] presets
          :let [w (conj where k)]
          msg (cond
                (nil? (preset-slot k)) [(str "preset slot " (pr-str k) " must be a single digit 0-9")]
                (not (map? p)) ["a preset is {:input \"…\" :label \"…\"}"]
                :else (concat
                       (when-not (contains? p :input) [":input is required — the text Codex submits"])
                       (for [f [:input :label] :when (and (contains? p f) (not (string? (get p f))))]
                         (str f " must be a string"))
                       (for [f (keys p) :when (not (preset-fields f))]
                         (str "unknown preset field " (pr-str f) " — codex reads :input and :label"))))]
      [:error w msg])))

;; ---------------------------------------------------------------- status line

(def status-line-items
  "`[tui] status_line` items codex-cli 0.169.0 renders, in the order its
   `/statusline` picker lists them. Codex loads any string here and drops an
   unknown one at startup with a warning (\"Ignored invalid status line
   items\"), so a typo would otherwise only show inside the TUI."
  ["project-name" "current-dir" "run-state" "thread-name" "thread-title"
   "context-remaining" "context-used" "five-hour-limit" "weekly-limit"
   "codex-version" "used-tokens" "total-input-tokens" "total-output-tokens"
   "thread-credits" "estimated-thread-cost" "thread-id" "fast-mode" "daybreak"
   "model" "model-with-reasoning" "reasoning" "task-progress" "hostname"
   "pull-request-number" "branch-changes" "permissions" "approval-mode"
   "context-window-size" "raw-output" "workspace-headline" "profile" "provider"
   "git-branch"])

(def status-line-aliases
  "Older spellings 0.169.0 still renders without a warning; its picker
   offers only the names above."
  #{"project" "model-name"})

(defn status-line?
  "Is `v` Codex's footer shape — a list of items — rather than Claude Code's
   status-line command object, which a unified settings row also carries?"
  [v]
  (sequential? v))

(defn- status-line-findings [items where]
  (let [known (into (set status-line-items) status-line-aliases)]
    (for [item items
          :let [s (when (or (keyword? item) (string? item)) (u/key-str item))]
          :when (not (known s))]
      [:error where (str "unknown status-line item " (pr-str item) " — codex 0.169.0 accepts: "
                         (str/join ", " status-line-items))])))

;; ---------------------------------------------------------------- permission profiles

(def profile-fields
  "`[permissions.<name>]` in 0.169.0 (`PermissionProfileToml`, five fields).
   Keys inside a profile keep Codex's own spelling."
  ["description" "extends" "workspace_roots" "filesystem" "network"])

(def builtin-profiles
  "What `extends` may name with a leading colon; anything else with one is a
   load error (`cannot extend unsupported built-in profile`)."
  #{":read-only" ":workspace"})

(def filesystem-modes #{"read" "write" "none" "deny"})

(defn- s-of [v] (when (or (keyword? v) (string? v)) (u/key-str v)))

(defn- fs-root?
  "Codex: `filesystem path … must be absolute, use ~/..., or start with :`."
  [s]
  (boolean (and s (re-find #"^(/|~/|:)" s))))

(defn- filesystem-findings [fs where]
  (if-not (map? fs)
    [[:error where ":filesystem maps a path to \"read\", \"write\", \"none\" or \"deny\""]]
    (for [[k v] fs
          :let [path (s-of k)
                w (conj where k)
                mode? #(filesystem-modes (s-of %))]
          msg (cond
                (= "glob_scan_max_depth" path) (when-not (nat-int? v) ["glob_scan_max_depth must be a whole number"])
                (not (fs-root? path)) [(str (pr-str k) " must be absolute, start with ~/ or name a :special root")]
                (map? v) (for [[sub m] v :when (not (mode? m))]
                           (str (pr-str sub) " under " path " must be read, write, none or deny"))
                (not (mode? v)) [(str path " must be read, write, none or deny, or map subpaths to one")])]
      [:error w msg])))

(defn- network-findings [net where]
  (if-not (map? net)
    [[:error where ":network is a map of codex network settings"]]
    (concat
     (for [[k v] net :when (and (= "enabled" (s-of k)) (not (boolean? v)))]
       [:error (conj where k) "enabled must be true or false"])
     (for [[k v] net :when (= "domains" (s-of k))
           msg (if (map? v)
                 (for [[d a] v :when (not (#{"allow" "deny"} (s-of a)))]
                   (str (pr-str d) " must be allow or deny"))
                 ["domains maps a host to allow or deny"])]
       [:error (conj where k) msg]))))

(defn- profile-findings [profiles where]
  (if-not (map? profiles)
    [[:error where ":permission-profiles maps a profile name to its [permissions.<name>] table"]]
    (for [[nm p] profiles
          :let [w (conj where nm)
                fields (when (map? p) (into {} (map (fn [[k v]] [(s-of k) [k v]])) p))]
          [w msg] (cond
                    (or (nil? (s-of nm)) (str/blank? (s-of nm)) (str/starts-with? (s-of nm) ":"))
                    [[w (str "profile name " (pr-str nm) " must not be blank or start with : (those are built-ins)")]]
                    (not (map? p)) [[w "a profile is a map: {:extends \":workspace\" :filesystem {…} :network {…}}"]]
                    :else
                    (concat
                     (for [[s [k _]] fields :when (not (some #{s} profile-fields))]
                       [(conj w k) (str "unknown profile field " (pr-str k) " — codex reads "
                                        (str/join ", " profile-fields))])
                     (when-let [[k v] (fields "extends")]
                       (cond
                         (not (string? v)) [[(conj w k) "extends must be a string: a profile name or :read-only / :workspace"]]
                         (and (str/starts-with? v ":") (not (builtin-profiles v)))
                         [[(conj w k) (str v " is no built-in profile — codex has :read-only and :workspace")]]))
                     (when-let [[k v] (fields "description")]
                       (when-not (string? v) [[(conj w k) "description must be a string"]]))
                     (when-let [[k v] (fields "workspace_roots")]
                       (if (map? v)
                         (for [[r b] v :when (not (boolean? b))] [(conj w k r) "must be true or false"])
                         [[(conj w k) "workspace_roots maps a root to true or false"]]))
                     (when-let [[k v] (fields "filesystem")]
                       (map rest (filesystem-findings v (conj w k))))
                     (when-let [[k v] (fields "network")]
                       (map rest (network-findings v (conj w k))))))]
      [:error w msg])))

;; ---------------------------------------------------------------- hooks

(def hook-events
  "`hooks.json` events codex-cli 0.169.0 dispatches (`HookEventsToml`). It
   loads any other key without a word, and that hook never fires."
  ["SessionStart" "SessionEnd" "UserPromptSubmit" "PreModelRequest" "PostModelRequest"
   "PreToolUse" "PermissionRequest" "PostToolUse" "PreCompact" "PostCompact"
   "SubagentStart" "SubagentStop" "Stop" "Interrupt"])

(def hook-entry-keys
  "Declared field -> the command handler's field in 0.169.0. `:event`,
   `:matcher` and `:id` place the hook; they are not part of it."
  {:command :command :command-windows :commandWindows :timeout :timeout :async :async
   :status-message :statusMessage :additional-context-limit :additionalContextLimit})

(def ^:private hook-placement #{:event :matcher :type :hook-id-missing})

(defn- hook-findings [hooks where]
  (if-not (map? hooks)
    [[:error where ":hooks maps an id to {:event … :command …}, or an event to a vector of them"]]
    (for [[id h] hooks
          :let [w (conj where (if (:hook-id-missing h) (:event h) id))]
          msg (if-not (map? h)
                ["a hook is a map: {:event :SessionStart :command \"…\"}"]
                (concat
                 (when (:hook-id-missing h) ["hook grouped under :hooks needs :id"])
                 (cond
                   (nil? (:event h)) ["hook needs :event"]
                   (not (some #{(s-of (:event h))} hook-events))
                   [(str "unknown event " (pr-str (:event h)) " — codex 0.169.0 dispatches "
                         (str/join ", " hook-events))])
                 (when-not (and (string? (:command h)) (not (str/blank? (:command h))))
                   ["hook needs :command, a string"])
                 (when-not (contains? #{nil "command" :command} (:type h))
                   ["codex hooks are command hooks — :type, when given, is \"command\""])
                 (when-not (or (nil? (:matcher h)) (string? (:matcher h))) [":matcher must be a string"])
                 (when-not (or (nil? (:timeout h)) (pos-int? (:timeout h))) [":timeout is whole seconds"])
                 (when-not (or (nil? (:async h)) (boolean? (:async h))) [":async must be true or false"])
                 (for [k [:status-message :command-windows]
                       :when (and (contains? h k) (not (string? (get h k))))]
                   (str k " must be a string"))
                 (when-not (or (nil? (:additional-context-limit h)) (nat-int? (:additional-context-limit h)))
                   [":additional-context-limit must be a whole number"])
                 (for [k (keys h) :when (not (or (hook-placement k) (contains? hook-entry-keys k)))]
                   (str "unknown hook field " (pr-str k) " — codex reads "
                        (str/join ", " (map pr-str (concat [:event :matcher] (keys hook-entry-keys))))))))]
      [:error w msg])))

;; ---------------------------------------------------------------- findings

(defn findings
  "[level where message] for the codex settings map declared at `where`."
  [settings where]
  (when (map? settings)
    (concat
     (when (contains? settings :presets)
       (preset-findings (:presets settings) (conj where :presets)))
     (when (status-line? (:status-line settings))
       (status-line-findings (:status-line settings) (conj where :status-line)))
     (when (contains? settings :hooks)
       (hook-findings (:hooks settings) (conj where :hooks)))
     (when (contains? settings :permission-profiles)
       (concat
        (profile-findings (:permission-profiles settings) (conj where :permission-profiles))
        ;; codex refuses to start on [permissions] tables with no default
        (when (and (seq (:permission-profiles settings)) (not (contains? settings :sandbox)))
          [[:warn (conj where :permission-profiles)
            (str "codex will not start with [permissions] profiles and no default_permissions"
                 " — declare :sandbox (agentctl writes the profiles only if config.toml already names one)")]]))))))

(defn errors
  "The error messages `findings` has for `settings`, joined, or nil."
  [settings where]
  (some->> (findings settings where)
           (filter #(= :error (first %)))
           (map (fn [[_ w m]] (str (str/join " " (map u/kw->str (drop (count where) w))) ": " m)))
           seq
           (str/join "; ")))
