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

;; ---------------------------------------------------------------- findings

(defn findings
  "[level where message] for the codex settings map declared at `where`."
  [settings where]
  (when (map? settings)
    (concat
     (when (contains? settings :presets)
       (preset-findings (:presets settings) (conj where :presets)))
     (when (status-line? (:status-line settings))
       (status-line-findings (:status-line settings) (conj where :status-line))))))

(defn errors
  "The error messages `findings` has for `settings`, joined, or nil."
  [settings where]
  (some->> (findings settings where)
           (filter #(= :error (first %)))
           (map (fn [[_ w m]] (str (str/join " " (map u/kw->str (drop (count where) w))) ": " m)))
           seq
           (str/join "; ")))
