(ns agentctl.refs
  "Secret / indirection references.

   Surface forms accepted in agents.edn:
     $FOO                     symbol   -> env var FOO
     \"$FOO\"                   string   -> env var FOO
     \"!env://FOO\"             string   -> env var FOO
     \"!bw://item/field\"       string   -> bitwarden item + field
     \"!bw://folder/item/field\"
     \"!file://~/path\"         string   -> file contents (trimmed)
     \"!cmd://some command\"    string   -> stdout of command (trimmed)
     \"literal\"                string   -> literal value

   Internal form: {:ref/kind :env|:bw|:file|:cmd|:literal ...}"
  (:require [agentctl.util :as u]
            [clojure.string :as str]))

(defn ref?
  "A ref is a map carrying :ref/kind. Tested over `keys` rather than by lookup:
   rendering asks this of `u/norm`-ed maps, whose keys are strings in a sorted
   map, and a keyword lookup there dies in the comparator."
  [v]
  (and (map? v) (boolean (some #{:ref/kind} (keys v)))))

(defn parse
  "Normalize a declared value into a ref map. Non-secret scalars become :literal."
  [v]
  (cond
    (ref? v) v

    (symbol? v)
    (let [s (str v)]
      (if (str/starts-with? s "$")
        {:ref/kind :env :ref/var (subs s 1) :ref/raw s}
        {:ref/kind :literal :ref/value s :ref/raw s}))

    (string? v)
    (cond
      (str/starts-with? v "!bw://")
      (let [segs (str/split (subs v 6) #"/")]
        {:ref/kind :bw
         :ref/raw v
         :ref/folder (when (= 3 (count segs)) (first segs))
         :ref/item (if (= 3 (count segs)) (second segs) (first segs))
         :ref/field (last segs)})

      (str/starts-with? v "!env://") {:ref/kind :env :ref/var (subs v 7) :ref/raw v}
      (str/starts-with? v "!file://") {:ref/kind :file :ref/path (subs v 8) :ref/raw v}
      (str/starts-with? v "!cmd://") {:ref/kind :cmd :ref/cmd (subs v 7) :ref/raw v}
      (str/starts-with? v "$") {:ref/kind :env :ref/var (subs v 1) :ref/raw v}
      :else {:ref/kind :literal :ref/value v :ref/raw v})

    (nil? v) nil
    :else {:ref/kind :literal :ref/value v :ref/raw v}))

(defn secret-ref? [r] (and (ref? r) (contains? #{:bw :env :file :cmd} (:ref/kind r))))

(defn describe
  "Human-safe rendering of a ref. Never leaks a resolved secret."
  [r]
  (cond
    (nil? r) "<nil>"
    (not (ref? r)) (pr-str r)
    :else (case (:ref/kind r)
            :env (str "env:" (:ref/var r))
            :bw (str "bw:" (str/join "/" (remove nil? [(:ref/folder r) (:ref/item r) (:ref/field r)])))
            :file (str "file:" (u/tilde (u/expand (:ref/path r))))
            :cmd (str "cmd:" (:ref/cmd r))
            :literal (let [v (:ref/value r)]
                       (if (string? v) v (pr-str v))))))

;; ---------------------------------------------------------------- resolution

(def ^:private cache (atom {}))

(defn- bw-unlocked? []
  (let [{:keys [exit out]} (u/sh "bw" "status")]
    (and (zero? exit) (str/includes? out "\"unlocked\""))))

(def bw-state
  (delay
    (cond
      (nil? (u/which "bw")) :absent
      (bw-unlocked?) :unlocked
      :else :locked)))

(defn- bw-fetch [{:ref/keys [item field]}]
  (case @bw-state
    :absent {:status :error :message "bw CLI not on PATH"}
    :locked {:status :unknown :message "bitwarden vault locked"}
    (let [{:keys [exit out err]} (u/sh "bw" "get" "item" item)]
      (if-not (zero? exit)
        {:status :missing :message (str "bw item not found: " item " (" err ")")}
        (let [it (try (cheshire.core/parse-string out true) (catch Exception _ nil))
              fields (into {} (map (juxt :name :value)) (:fields it))
              login (:login it)
              v (or (get fields field)
                    (when (= field "password") (:password login))
                    (when (= field "username") (:username login))
                    (when (and (:username login) (= field (:username login))) (:password login))
                    (:password login))]
          (if v
            {:status :ok :value v}
            {:status :missing :message (str "field not found on bw item: " item "/" field)}))))))

(defn resolve-ref
  "Resolve a ref. Returns {:status :ok|:missing|:unknown|:error :value ... :message ...}.
   :unknown means \"cannot tell right now\" (e.g. locked vault) and must not fail validate."
  [r]
  (cond
    (nil? r) {:status :ok :value nil}
    (not (ref? r)) {:status :ok :value r}
    :else
    (or (get @cache (:ref/raw r))
        (let [res (case (:ref/kind r)
                    :literal {:status :ok :value (:ref/value r)}
                    :env (if-let [v (System/getenv (:ref/var r))]
                           {:status :ok :value v}
                           {:status :missing :message (str "env var unset: " (:ref/var r))})
                    :file (if-let [v (u/slurp-safe (:ref/path r))]
                            {:status :ok :value (str/trim v)}
                            {:status :missing :message (str "file not readable: " (:ref/path r))})
                    :cmd (let [{:keys [exit out err]} (u/sh "sh" "-c" (:ref/cmd r))]
                           (if (zero? exit)
                             {:status :ok :value (str/trim out)}
                             {:status :error :message (str "command failed: " err)}))
                    :bw (bw-fetch r))]
          (swap! cache assoc (:ref/raw r) res)
          res))))

(defn resolved-value [r]
  (let [{:keys [status value]} (resolve-ref r)]
    (when (= :ok status) value)))

;; ---------------------------------------------------------------- redaction

(def secret-key-pattern #"(?i)(api[-_]?key|apikey|token|secret|password|passwd|credential|bearer)")

(def indirection-key-pattern
  "Keys that name where a secret lives rather than holding one: `:key-name`
   points at an entry in llm's keys.json, and anything spelled `…-env` names an
   environment variable. Their values are identifiers and belong in a config
   file — flagging them as plaintext credentials trains the reader to ignore
   the warning that matters."
  #"(?i)(^key-name$|[-_]env$|[-_]env[-_]var$|[-_]key[-_]name$)")

(def credential-value-patterns
  "Vendor prefixes nothing but a credential carries."
  [#"^sk-[A-Za-z0-9\-_]{10,}"
   #"^xox[abcdeprs]-"
   #"^ghp_[A-Za-z0-9]{20,}"
   #"^gh[pousr]_[A-Za-z0-9]{20,}"
   #"^ey[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\."])

(def secret-value-patterns
  ;; bare hex is a guess: right for import, where a false positive only costs a
  ;; needless `!bw://` placeholder, and wrong for display, where it would mask a
  ;; git revision
  (conj credential-value-patterns #"^[A-Fa-f0-9]{40,}$"))

(defn secret-shaped?
  "True when key name or value looks like a credential."
  [k v]
  (boolean
   (or (and k (re-find secret-key-pattern (u/kw->str k)))
       (and (string? v) (some #(re-find % v) secret-value-patterns)))))

(defn credential?
  "The display-side test, deliberately narrower than `secret-shaped?`: masked
   only when the key names a credential or the value carries a vendor prefix."
  [k v]
  (boolean
   (or (and k (re-find secret-key-pattern (u/kw->str k)))
       (and (string? v) (some #(re-find % v) credential-value-patterns)))))

(defn redact
  "Replace a discovered secret literal with a bw placeholder ref string."
  [scope k]
  (str "!bw://" scope "/" (u/kw->str k)))

(defn mask [v]
  (let [s (str v)]
    (if (<= (count s) 8) "********" (str (subs s 0 4) "…" (subs s (- (count s) 2))))))
