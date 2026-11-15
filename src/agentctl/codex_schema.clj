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

;; ---------------------------------------------------------------- findings

(defn findings
  "[level where message] for the codex settings map declared at `where`."
  [settings where]
  (when (map? settings)
    (concat
     (when (contains? settings :presets)
       (preset-findings (:presets settings) (conj where :presets))))))

(defn errors
  "The error messages `findings` has for `settings`, joined, or nil."
  [settings where]
  (some->> (findings settings where)
           (filter #(= :error (first %)))
           (map (fn [[_ w m]] (str (str/join " " (map u/kw->str (drop (count where) w))) ": " m)))
           seq
           (str/join "; ")))
