(ns agentctl.refs
  "Secret / indirection references.

   Surface forms accepted in agents.edn:
     $FOO                     symbol   -> env var FOO
     \"$FOO\"                   string   -> env var FOO
     \"$HOME/bin/x\"            string   -> literal, with $VAR and ~ expanded
     \"!env://FOO\"             string   -> env var FOO
     \"!bw://item/field\"       string   -> bitwarden item + field
     \"!bw://folder/item/field\"
     \"!file://~/path\"         string   -> file contents (trimmed)
     \"!cmd://some command\"    string   -> stdout of command (trimmed)
     \"literal\"                string   -> literal value
     \"Bearer !bw://item/f\"     string   -> template: text around embedded refs

   Internal form: {:ref/kind :env|:bw|:file|:cmd|:literal|:template ...}"
  (:require [agentctl.util :as u]
            [clojure.string :as str]))

(defn ref?
  "A ref is a map carrying :ref/kind. Tested over `keys` rather than by lookup:
   rendering asks this of `u/norm`-ed maps, whose keys are strings in a sorted
   map, and a keyword lookup there dies in the comparator."
  [v]
  (and (map? v) (boolean (some #{:ref/kind} (keys v)))))

(defn- ref-error
  "Validate marked maps without including their potentially secret contents."
  [r]
  (when (ref? r)
    (let [text? #(and (string? %) (not (str/blank? %)))]
      (case (:ref/kind r)
        :env (when-not (and (text? (:ref/var r))
                           (not (re-find #"[=\u0000]" (:ref/var r))))
               "invalid reference: :env requires a nonblank variable name without = or NUL")
        :bw (when-not (and (text? (:ref/item r)) (text? (:ref/field r))
                          (or (nil? (:ref/folder r)) (text? (:ref/folder r))))
              "invalid reference: :bw requires an item and field, and an optional nonblank folder")
        :file (when-not (text? (:ref/path r))
                "invalid reference: :file requires a nonblank path")
        :cmd (when-not (text? (:ref/cmd r))
               "invalid reference: :cmd requires a nonblank command")
        :literal (when-not (contains? r :ref/value)
                   "invalid reference: :literal requires :ref/value")
        :template (some ref-error (filter ref? (:ref/parts r)))
        "invalid reference: unsupported kind"))))

(defn- dollar
  "A value spelled with a leading `$`. Only a whole `$NAME` is a reference to
   that variable. Anything longer — `$HOME/bin/x` — is a string with a variable
   in it: read as a reference it names a variable that cannot exist, and the
   value is dropped without a word."
  [s]
  (if-let [[_ n] (re-matches #"\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?" s)]
    {:ref/kind :env :ref/var n :ref/raw s}
    {:ref/kind :literal :ref/value (u/expand s) :ref/raw s}))

(declare parse)

(def ^:private embedded-ref-pattern
  ;; a reference inside a longer string. `!{bw://item/field}` is bounded
  ;; explicitly, and is the spelling for one glued to text (a DSN's
  ;; `user:!{bw://db/pw}@host`). A bare one runs up to a space, a quote,
  ;; `:`, `!` (the next reference), or a URL's `&`/`#`. A `!cmd://` runs to
  ;; the end of its line, so it is a reference only as a whole value
  #"!\{(?:bw|env|file)://[^}]+\}|!(?:bw|env|file)://[^\s\"'&#,;!:]+")

(def ^:private embedded-ref-or-var-pattern
  #"!\{(?:bw|env|file)://[^}]+\}|!(?:bw|env|file)://[^\s\"'&#,;!:]+|\$\{[A-Za-z_][A-Za-z0-9_]*\}|\$[A-Za-z_][A-Za-z0-9_]*")

(def ^:private ref-marker
  #"!(?:bw|env|file|cmd)://|!\{(?:bw|env|file)://")

(defn- embeds-ref?
  "Whether a string holds a reference anywhere but as the whole of it: one
   after its first character, as in `!bw://db/user:!bw://db/pw`, or a
   braced one."
  [v]
  (or (str/starts-with? v "!{")
      (boolean (re-find ref-marker (subs v 1)))))

(defn- template
  "`s` as a template — its text with the references in it — when it embeds
   one: `Bearer !bw://docs/token`, `https://h/x?key=!bw://docs/key`."
  [s pattern]
  (let [m (re-matcher pattern s)]
    (loop [pos 0, parts []]
      (if (.find m)
        (recur (.end m) (cond-> parts
                          (< pos (.start m)) (conj (subs s pos (.start m)))
                          true (conj (let [g (.group m)]
                                       (parse (if (str/starts-with? g "!{")
                                                (str "!" (subs g 2 (dec (count g))))
                                                g))))))
        (let [parts (cond-> parts (< pos (count s)) (conj (subs s pos)))]
          (when (some ref? parts)
            {:ref/kind :template :ref/parts parts :ref/raw s}))))))

(defn parse
  "Normalize a declared value into a ref map. Non-secret scalars become :literal.
   Malformed references throw ExceptionInfo without exposing their contents."
  [v]
  (let [r (cond
            (ref? v) v

            (symbol? v)
            (let [s (str v)]
              (if (str/starts-with? s "$")
                (dollar s)
                {:ref/kind :literal :ref/value s :ref/raw s}))

            (and (string? v) (seq v) (embeds-ref? v))
            (or (template v embedded-ref-pattern)
                {:ref/kind :literal :ref/value v :ref/raw v})

            (string? v)
            (cond
              (str/starts-with? v "!bw://")
              (let [segs (str/split (subs v 6) #"/")]
                {:ref/kind :bw
                 :ref/raw v
                 :ref/folder (when (= 3 (count segs)) (first segs))
                 :ref/item (if (= 3 (count segs)) (second segs) (first segs))
                 ;; a bare `!bw://item` is the item's login password, said
                 ;; here rather than left to a fallback in the lookup
                 :ref/field (if (= 1 (count segs)) "password" (last segs))})

              (str/starts-with? v "!env://") {:ref/kind :env :ref/var (subs v 7) :ref/raw v}
              (str/starts-with? v "!file://") {:ref/kind :file :ref/path (subs v 8) :ref/raw v}
              (str/starts-with? v "!cmd://") {:ref/kind :cmd :ref/cmd (subs v 7) :ref/raw v}
              (str/starts-with? v "$") (dollar v)
              :else (or (template v embedded-ref-pattern)
                        {:ref/kind :literal :ref/value v :ref/raw v}))

            (nil? v) nil
            :else {:ref/kind :literal :ref/value v :ref/raw v})]
    (when-let [error (ref-error r)]
      (throw (ex-info error {:type ::invalid-ref})))
    r))

(defn secret-ref? [r] (and (ref? r) (contains? #{:bw :env :file :cmd :template} (:ref/kind r))))

(defn parse-embedded
  "`parse`, for a value that carries its credential inside other text — an
   HTTP header (`Bearer $TOKEN`) or a URL (`…?key=$KEY`): a `$VAR` inside
   it is a reference too, resolved and masked as a whole `$VAR` is."
  [v]
  (let [r (parse v)]
    (if (and (string? v) (= :literal (:ref/kind r)))
      (or (template v embedded-ref-or-var-pattern) r)
      r)))

(defn uses-bw?
  "Whether resolving `r` asks the vault."
  [r]
  (case (:ref/kind r)
    :bw true
    :template (boolean (some uses-bw? (filter ref? (:ref/parts r))))
    false))

(defn describe
  "Human-safe rendering of a ref. Never leaks a resolved secret."
  [r]
  (cond
    (nil? r) "<nil>"
    (not (ref? r)) (pr-str r)
    (ref-error r) "<invalid reference>"
    :else (case (:ref/kind r)
            :env (str "env:" (:ref/var r))
            :bw (str "bw:" (str/join "/" (remove nil? [(:ref/folder r) (:ref/item r) (:ref/field r)])))
            :file (str "file:" (u/tilde (u/expand (:ref/path r))))
            :cmd (str "cmd:" (:ref/cmd r))
            :template (apply str (map #(if (ref? %) (str "{" (describe %) "}") %) (:ref/parts r)))
            :literal (let [v (:ref/value r)]
                       (if (string? v) v (pr-str v))))))

;; ---------------------------------------------------------------- resolution

(def ^:private cache (atom {}))

(def ^:private resolved-secrets
  "Every value a ref other than a literal resolved to. Output is scrubbed of
   them wherever they land: a value's name says nothing reliable about whether
   it is a credential (`x-docs`, `DOCS_ENV`), but where it came from does."
  (atom #{}))

(defn- bw-unlocked? []
  (let [{:keys [exit out]} (u/sh "bw" "status")]
    (and (zero? exit) (str/includes? out "\"unlocked\""))))

(defn- probe-bw []
  (delay
    (cond
      (nil? (u/which "bw")) :absent
      (bw-unlocked?) :unlocked
      :else :locked)))

(def ^:private bw-probe (atom (probe-bw)))

(def bw-state
  "The vault: `:absent`, `:locked` or `:unlocked`, asked once and then
   remembered until `forget!`."
  (reify clojure.lang.IDeref
    (deref [_] @@bw-probe)))

(defn forget!
  "Drop every resolved value and the vault's state, so the next resolution
   asks again. A CLI run resolves once; a long-lived process (the GUI) calls
   this per request, or it would never see a rotated secret or an unlocked
   vault."
  []
  (reset! cache {})
  (reset! resolved-secrets #{})
  (reset! bw-probe (probe-bw)))

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
              named? (contains? fields field)
              ;; a field the item names is the answer, even when it is empty:
              ;; falling through to the login would send its password instead.
              ;; Otherwise only `password`, `username` and the login's own
              ;; username reach the login: any other name — a typo — is
              ;; missing, never the password
              v (if named?
                  (get fields field)
                  (cond
                    (= field "password") (:password login)
                    (= field "username") (:username login)
                    (and (:username login) (= field (:username login))) (:password login)))]
          (cond
            ;; an emptied field reads back as null, "" or the text "null";
            ;; written out, any of them is a header that authenticates nothing
            (and named? (or (nil? v) (contains? #{"" "null"} (str/trim (str v)))))
            {:status :missing :message (str "field is empty on bw item: " item "/" field)}
            (nil? v) {:status :missing
                      :message (str "field not found on bw item: " item "/" field
                                    ;; a ref that once reached the login through
                                    ;; a fallback says where to point it now
                                    (when (and (not named?) (:password login))
                                      (str " — the item has a login: did you mean " item "/password?")))}
            (contains? #{"" "null"} (str/trim (str v)))
            {:status :missing :message (str "field is empty on bw item: " item "/" field)}
            :else {:status :ok :value v}))))))

(defn resolve-ref
  "Resolve a ref. Returns {:status :ok|:missing|:unknown|:error :value ... :message ...}.
   :unknown means \"cannot tell right now\" (e.g. locked vault) and must not fail validate."
  [r]
  (cond
    (nil? r) {:status :ok :value nil}
    (not (ref? r)) {:status :ok :value r}
    (ref-error r) {:status :error :message (ref-error r)}
    :else
    (or (get @cache r)
        (let [res (case (:ref/kind r)
                    :literal {:status :ok :value (:ref/value r)}
                    ;; an empty value is missing, as an emptied vault field
                    ;; is: written out, it is a header or env var that
                    ;; authenticates nothing
                    :env (let [v (System/getenv (:ref/var r))]
                           (cond
                             (nil? v) {:status :missing :message (str "env var unset: " (:ref/var r))}
                             (str/blank? v) {:status :missing :message (str "env var is empty: " (:ref/var r))}
                             :else {:status :ok :value v}))
                    :file (let [v (u/slurp-safe (:ref/path r))]
                            (cond
                              (nil? v) {:status :missing :message (str "file not readable: " (:ref/path r))}
                              (str/blank? v) {:status :missing :message (str "file is empty: " (:ref/path r))}
                              :else {:status :ok :value (str/trim v)}))
                    :cmd (let [{:keys [exit out err]} (u/sh "sh" "-c" (:ref/cmd r))]
                           (cond
                             (not (zero? exit)) {:status :error :message (str "command failed: " err)}
                             (str/blank? out) {:status :missing :message "command printed nothing"}
                             :else {:status :ok :value (str/trim out)}))
                    :bw (bw-fetch r)
                    ;; every reference in it must resolve: a header with
                    ;; half its credential authenticates nothing
                    :template (let [rs (map #(if (ref? %) (resolve-ref %) {:status :ok :value %})
                                            (:ref/parts r))]
                                (or (first (remove #(= :ok (:status %)) rs))
                                    {:status :ok :value (apply str (map :value rs))})))]
          (swap! cache assoc r res)
          ;; a value that names a file or directory (`$HOME`) is no secret,
          ;; and masked it would take every path under it along
          (when (and (= :ok (:status res)) (not= :literal (:ref/kind r))
                     (string? (:value res)) (not (str/blank? (:value res)))
                     (not (and (re-find #"^[/~]" (:value res)) (u/exists? (:value res)))))
            (swap! resolved-secrets conj (:value res)))
          res))))

(defn resolved-value [r]
  (let [{:keys [status value]} (resolve-ref r)]
    (when (= :ok status) value)))

;; ---------------------------------------------------------------- redaction

;; `authorization` is here for the http-MCP header, whose value is the whole
;; credential while the key name says nothing about it
(def secret-key-pattern #"(?i)(api[-_]?key|apikey|token|secret|password|passwd|credential|bearer|authorization)")

(def indirection-key-pattern
  "Keys that name where a secret lives rather than holding one: `:key-name`
   points at an entry in llm's keys.json, and anything spelled `…-env` names an
   environment variable. Their values are identifiers and belong in a config
   file — flagging them as plaintext credentials trains the reader to ignore
   the warning that matters."
  #"(?i)(^key-name$|[-_]env$|[-_]env[-_]var$|[-_]key[-_]name$)")

(def names-a-variable-pattern
  "A key that names the *place* a credential lives rather than holding it.
   Kimi spells this `apiKeyEnv`: its value is the name of an environment
   variable, and masking it in the plan would hide which variable to set.
   The vendor-prefix test below still masks a real credential under it."
  #"(?i)[a-z]env$")

(def plain-words
  "Values common enough as settings, and short enough, to be no one's secret."
  #{"true" "false" "yes" "no" "on" "off" "enabled" "disabled"
    "trace" "debug" "info" "warn" "warning" "error" "fatal" "silent" "verbose" "quiet"
    "fast" "slow" "dev" "development" "prod" "production" "test" "staging" "local"})

(defn credential-flag
  "The name a credential-looking flag carries — `api-key` for `--api-key`,
   `token` for `-token` — or nil. A flag naming where the credential lives
   (`--token-file`, `--api-key-env`) takes a path or a name, and is not one."
  [a]
  (when-let [[_ nm] (and (string? a) (re-matches #"--?([A-Za-z][A-Za-z0-9_-]*)" a))]
    (when (and (or (re-find secret-key-pattern nm) (re-matches #"(?i)auth|key" nm))
               (not (re-find indirection-key-pattern nm))
               (not (re-find #"(?i)[-_](file|path|dir|var|name)$" nm)))
      nm)))

(defn- secret-ref-text [a]
  (when (string? a)
    (let [r (try (parse a) (catch clojure.lang.ExceptionInfo _ nil))]
      (when (secret-ref? r) r))))

(declare dollar-ref? takes-credential?)

(defn arg-refs
  "`[index label ref prefix]` for every reference argv carries: a `!bw://`,
   `!env://`, `!file://` or `!cmd://` anywhere, whole or embedded in an
   argument (`Authorization: Bearer !bw://docs/token`), and a whole `$VAR`
   where a credential goes — after a credential-looking flag or `-t`/`-k`,
   or as such a flag's `=value` (`prefix` is then `--token=`). Anywhere else
   a `$VAR` is text agentctl expands when it reads the config. `label` names
   the place: the flag, or `args[i]`."
  [args]
  (loop [i 0, xs (seq args), flag nil, acc []]
    (if-not xs
      acc
      (let [a (first xs)
            [_ f v] (when (string? a) (re-matches #"(--?[A-Za-z][A-Za-z0-9_-]*)=(.+)" a))
            rv (when f (secret-ref-text v))
            ra (secret-ref-text a)]
        (cond
          ;; `--opt=!cmd://…` is a command inside a longer argument: text
          (and rv (not= :cmd (:ref/kind rv)) (or (takes-credential? f) (not (dollar-ref? rv))))
          (recur (inc i) (next xs) nil (conj acc [i f rv (str f "=")]))

          (and ra (or flag (not (dollar-ref? ra))))
          (recur (inc i) (next xs) nil (conj acc [i (or flag (str "args[" i "]")) ra ""]))

          :else (recur (inc i) (next xs) (when (and (not f) (takes-credential? a)) a) acc))))))

(def ^:private literal-ref-pattern
  #"!\{?(?:bw|env|file|cmd)://|\$\{[A-Za-z_][A-Za-z0-9_]*\}|\$[A-Za-z_][A-Za-z0-9_]*")

(defn literal-ref
  "The reference spelling `s` still carries once everything that resolves
   has been resolved — a `$VAR` nothing expanded, a `!cmd://` inside other
   text — or nil. It would be written as text. Only the scheme or the
   variable's name is returned, never what follows."
  [s]
  (when (string? s)
    (when-let [hit (re-find literal-ref-pattern s)]
      (if (str/starts-with? hit "!") (str hit "…") hit))))

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
   only when the key names a credential or the value carries a vendor prefix.
   A key that names *where* the credential lives — kimi's `apiKeyEnv`, codex's
   `bearer_token_env_var` — is not one: its value is an identifier, and
   masking it would hide which variable to set. A value that looks like a
   credential is still masked under it."
  [k v]
  (boolean
   (or (and k (re-find secret-key-pattern (u/kw->str k))
            (not (re-find indirection-key-pattern (u/kw->str k)))
            (not (re-find names-a-variable-pattern (u/kw->str k))))
       (and (string? v) (some #(re-find % v) credential-value-patterns)))))

(defn redact
  "Replace a discovered secret literal with a bw placeholder ref string."
  [scope k]
  (str "!bw://" scope "/" (u/kw->str k)))

(defn mask [v]
  (let [s (str v)]
    (if (<= (count s) 8) "********" (str (subs s 0 4) "…" (subs s (- (count s) 2))))))

(defn masked?
  "A string `mask` produced. Masked once, a value is not masked again: a mask
   of a mask says less, and says it differently on each side of a diff."
  [v]
  (boolean (and (string? v) (re-matches #"(?s)(?:\*{8}|.{4}….{2})(?: \(changed\))?" v))))

(def ^:private min-embedded
  "How long a resolved value must be before it is searched for inside other
   text. A short one (`1`, `on`) would mask half the plan; it is still masked
   where it is the whole value, and wherever argv puts a credential."
  4)

(def ^:private embedded-pattern
  ;; a resolved value inside other text, as a token of its own: after a
  ;; space, a quote, `=` or `:`, and before a space, a quote or punctuation.
  ;; A value that is also a word in a host or a path (`config`) leaves the
  ;; URL and the path readable
  (memoize
   (fn [sec]
     (re-pattern (str "(?<![^\\s\"'=:,(\\[{<>])" (java.util.regex.Pattern/quote sec)
                      "(?![^\\s\"',;)\\]}>&])")))))

(defn resolved-secret?
  "True when `v` is, or carries as a token, a value a ref resolved to."
  [v]
  (boolean
   (and (string? v)
        (some #(or (= v %) (and (>= (count %) min-embedded) (re-find (embedded-pattern %) v)))
              @resolved-secrets))))

(defn- shown-as-is?
  "A value argv may show: already masked, or a reference (`$VAR`, `!bw://…`)
   rather than what it resolves to."
  [v]
  (or (masked? v) (boolean (re-find #"^[$!]" v))))

(def ^:private short-credential-flags
  "One-letter flags that take a token in many CLIs (`-t`, `-k`). Too common
   to mask whatever follows them, but a reference after one is a credential."
  #{"-t" "-k"})

(defn- dollar-ref? [r]
  (and (= :env (:ref/kind r)) (str/starts-with? (str (:ref/raw r)) "$")))

(defn- takes-credential? [flag]
  (boolean (or (credential-flag flag) (short-credential-flags flag))))

(defn- plain-arg?
  "A value after `-t` or `-k` that is plainly no credential: those letters
   take a timeout, a type or a key path as often as a token."
  [v]
  (boolean (or (re-matches #"-?\d+(\.\d+)?[a-z]{0,2}" v)
               (contains? plain-words (str/lower-case v))
               (re-find #"^[/~.]" v))))

(defn mask-argv
  "argv with the value at every credential position masked: the argument
   after a credential-looking flag, and such a flag's `=value`. Whatever
   the value is — resolved this run, read back from disk, or short enough
   to be found nowhere else — the position says what it is. After `-t` or
   `-k` a value is masked unless it is plainly something else."
  [args]
  (loop [xs (seq args), flag nil, out []]
    (if-not xs
      out
      (let [a (first xs)
            [_ f v] (when (string? a) (re-matches #"(--?[A-Za-z][A-Za-z0-9_-]*)=(.+)" a))
            hide? (fn [flag v] (and (not (shown-as-is? v))
                                    (or (credential-flag flag) (not (plain-arg? v)))))]
        (cond
          (and f (takes-credential? f))
          (recur (next xs) nil (conj out (if (hide? f v) (str f "=" (mask v)) a)))

          (and flag (string? a) (not (str/starts-with? a "-")))
          (recur (next xs) nil (conj out (if (hide? flag a) (mask a) a)))

          :else (recur (next xs) (when (and (not f) (takes-credential? a)) a) (conj out a)))))))

(def ^:private flag-value-pattern
  #"(?<![\w-])(--?[A-Za-z][A-Za-z0-9_-]*)(=|\s+)([^\s\"',\]})]+)")

(defn- mask-flag-values
  "`mask-argv` for argv already joined into text: a summary, a command line,
   a CLI's error echoing what it was given."
  [s]
  (str/replace s flag-value-pattern
               (fn [[whole f sep v]]
                 (if (and (takes-credential? f) (not (str/starts-with? v "-")) (not (shown-as-is? v))
                          (or (credential-flag f) (not (plain-arg? v))))
                   (str f sep (mask v))
                   whole))))

(def ^:private known-digests
  "Digests of every value a reference resolved to in a run that wrote, kept
   in the state file (`remember-digests`). A value read back from disk that
   no reference resolves to any more — the declaration dropped or replaced
   it — is still found by its digest, never by keeping it."
  (atom {:salt nil :digests #{}}))

(defn- digest [salt v]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (.getBytes (str salt "\u0000" v) "UTF-8"))))))

(defn load-digests!
  "Take the digests the state file `st` keeps, for masking this run's output."
  [st]
  (reset! known-digests {:salt (:secret-salt st) :digests (set (:secret-digests st))}))

(defn remember-digests
  "`st` with the digest of every value resolved this run added. The salt is
   made once per state file; at most the newest 1000 digests are kept."
  [st]
  (let [salt (or (:secret-salt st)
                 (apply str (map #(format "%02x" %)
                                 (let [b (byte-array 16)] (.nextBytes (java.security.SecureRandom.) b) b))))
        fresh (map #(digest salt %) @resolved-secrets)
        kept (vec (take-last 1000 (distinct (concat (:secret-digests st) fresh))))]
    (if (empty? kept)
      st
      (assoc st :secret-salt salt :secret-digests kept))))

(def ^:private token-pattern #"[^\s\"'=:,;&@?#\[\]{}()<>]+")

(defn- mask-known
  "`s` with every token whose digest a past run recorded masked."
  [s]
  (let [{:keys [salt digests]} @known-digests]
    (if (or (empty? digests) (nil? salt))
      s
      (reduce (fn [t tok]
                (if (and (not (masked? tok)) (contains? digests (digest salt tok)))
                  (str/replace t (embedded-pattern tok) (java.util.regex.Matcher/quoteReplacement (mask tok)))
                  t))
              s
              (distinct (cons s (re-seq token-pattern s)))))))

(defn scrub
  "`x` with every resolved value masked, at any depth and inside any string,
   and every credential position in argv masked, as a vector or as text.
   The last line of defence for output: a summary, a note, a command line or
   an error can carry one as surely as a diff can."
  [x]
  (let [secrets (sort-by (comp - count) @resolved-secrets)]
    (letfn [(clean [s]
              (mask-flag-values
               (if (some #(= s %) secrets)
                 (mask s)
                 (mask-known
                  (reduce (fn [t sec]
                            (if (>= (count sec) min-embedded)
                              (str/replace t (embedded-pattern sec) (java.util.regex.Matcher/quoteReplacement (mask sec)))
                              t))
                          s secrets)))))
            (walk [v]
              (cond
                (string? v) (clean v)
                (record? v) v
                (map? v) (into (empty v) (map (fn [[k x]] [k (walk x)])) v)
                (vector? v) (mapv walk (mask-argv v))
                (set? v) (into (empty v) (map walk) v)
                (seq? v) (map walk (mask-argv v))
                :else v))]
      (walk x))))
