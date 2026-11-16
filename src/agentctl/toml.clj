(ns agentctl.toml
  "Minimal TOML support.

   Reading goes through python3's stdlib tomllib (exact, no hand-rolled parser;
   `python!` picks the interpreter).
   Writing is deliberately *surgical*: the file is split into header-delimited
   segments and only the lines we own are rewritten, so comments, ordering and
   tool-written state (codex [hooks.state], [projects.*]) survive untouched."
  (:require [agentctl.util :as u]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- python

(def python-candidates
  "Interpreters tried, newest first, when AGENTCTL_PYTHON is unset. tomllib
   arrived in 3.11; 3.15's also reads TOML 1.1 (a multi-line inline table),
   which Codex accepts."
  ["python3.15" "python3.14" "python3.13" "python3.12" "python3.11" "python3"])

(defn- run-python
  "{:exit :out :err} of `script` run by `py` over stdin `text`; never throws."
  [py script text]
  (try @(p/process [py "-c" script] {:in (or text "") :out :string :err :string})
       (catch Exception e {:exit 127 :out "" :err (.getMessage e)})))

(defn- tomllib? [py] (zero? (:exit (run-python py "import tomllib" ""))))

(defn find-python
  "The interpreter to read TOML with: `env-python` when set — and then only
   if it has tomllib, never a silent fallback — else the first candidate on
   PATH that has it. nil for none."
  [env-python]
  (if (str/blank? env-python)
    (some #(when (and (u/which %) (tomllib? %)) %) python-candidates)
    (when (tomllib? env-python) env-python)))

(def ^:dynamic *python* "Overrides the lookup; tests rebind it." nil)

(def ^:private found-python (delay (find-python (System/getenv "AGENTCTL_PYTHON"))))

(defn no-python? [e] (= ::no-python (:type (ex-data e))))

(defn python!
  "The interpreter every TOML read and check runs, or an error saying what
   to install or set. There is no fallback: without tomllib a config reads as
   empty, the plan shows what exists as new, and apply never converges."
  []
  (or *python* @found-python
      (throw (ex-info (if-let [e (not-empty (System/getenv "AGENTCTL_PYTHON"))]
                        (str "AGENTCTL_PYTHON=" e " has no tomllib: TOML configs (Codex, Kimi) need python3 3.11+")
                        (str "TOML configs (Codex, Kimi) need python3 3.11+ with tomllib, and none is on PATH (tried "
                             (str/join ", " python-candidates) "): install one or set AGENTCTL_PYTHON"))
                      {:type ::no-python}))))

;; ---------------------------------------------------------------- read

(def ^:private py-load
  "import sys,tomllib,json;print(json.dumps(tomllib.loads(sys.stdin.read()),default=str))")

(defn parse-text
  "TOML text as a string-keyed nested map; throws when it does not parse."
  [text]
  (let [{:keys [exit out err]} (run-python (python!) py-load text)]
    (if (zero? exit)
      (json/parse-string out)
      (throw (ex-info (str "TOML parse failed: " (last (remove str/blank? (str/split-lines (str err)))))
                      {:err err})))))

(defn read-toml
  "Parse a TOML file into a keywordless nested map. nil when absent; throws
   when it does not parse, or when there is no interpreter to parse it with."
  [path]
  (when (u/exists? path)
    (try (parse-text (u/slurp-safe path))
         (catch clojure.lang.ExceptionInfo e
           (if (no-python? e) (throw e)
               (throw (ex-info (str "TOML parse failed: " path) (ex-data e))))))))

(defn read-toml-lenient
  "`read-toml`, but nil for a file that does not parse. A missing interpreter
   still throws."
  [path]
  (try (read-toml path)
       (catch Exception e (if (no-python? e) (throw e) nil))))

(defn get-path
  "Look up a dotted path in a parsed TOML map, e.g. [\"mcp_servers\" \"slack\"]."
  [toml path]
  (get-in toml (vec path)))

;; ---------------------------------------------------------------- encode

(defn quote-key
  "Quote a bare key only when TOML requires it."
  [k]
  (let [k (u/kw->str k)]
    (if (re-matches #"[A-Za-z0-9_-]+" k)
      k
      (str \" (str/replace k "\"" "\\\"") \"))))

(defn header-for
  "Build a table header string from path segments, quoting each as needed."
  [segments]
  (str/join "." (map quote-key segments)))

(defn encode-value [v]
  (cond
    (nil? v) "\"\""
    (string? v) (str \" (-> v (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \")
    (keyword? v) (encode-value (name v))
    (boolean? v) (str v)
    (number? v) (str v)
    (sequential? v) (str "[" (str/join ", " (map encode-value v)) "]")
    (map? v) (str "{" (str/join ", " (map (fn [[k x]] (str (quote-key k) " = " (encode-value x))) v)) "}")
    :else (encode-value (str v))))

;; ---------------------------------------------------------------- segments

(defn- skip-string
  "Index just past the string that opens at `i` of `s` (a quote char), or
   nil when it does not close on this line."
  [^String s i]
  (let [q (.charAt s i)
        n (count s)]
    (if (and (< (+ i 2) n) (= q (.charAt s (+ i 1))) (= q (.charAt s (+ i 2))))
      (when-let [j (str/index-of s (str q q q) (+ i 3))] (+ j 3))
      (loop [j (inc i)]
        (cond
          (>= j n) nil
          (and (= q \") (= \\ (.charAt s j))) (recur (+ j 2))
          (= q (.charAt s j)) (inc j)
          :else (recur (inc j)))))))

(defn- bare-key-char? [c]
  (or (Character/isLetterOrDigit (char c)) (= \_ c) (= \- c)))

(defn- ws-end [^String s i]
  (loop [i i] (if (and (< i (count s)) (Character/isWhitespace (.charAt s i))) (recur (inc i)) i)))

(defn- dotted-key
  "[parts end] for the dotted key that starts at column `i` of `line`: its
   parts unquoted, `end` the first column past it and the space after it.
   nil when no key starts there."
  [^String line i]
  (let [n (count line)]
    (loop [i (ws-end line i) parts []]
      (when (< i n)
        (let [c (.charAt line i)
              [part j] (if (#{\" \'} c)
                         (when-let [e (skip-string line i)]
                           [(cond-> (subs line (inc i) (dec e))
                              (= c \") (str/replace #"\\(.)" "$1"))
                            e])
                         (let [e (loop [j i] (if (and (< j n) (bare-key-char? (.charAt line j))) (recur (inc j)) j))]
                           (when (> e i) [(subs line i e) e])))]
          (when part
            (let [j (ws-end line j)]
              (if (and (< j n) (= \. (.charAt line j)))
                (recur (ws-end line (inc j)) (conj parts part))
                [(conj parts part) j]))))))))

(defn- parse-key
  "The dotted key a `key = value` line starts with, as unquoted parts, and
   the column just past its `=`. nil for anything else: a header, a comment,
   a blank line, or a line that continues the previous value."
  [^String line]
  (when-let [[parts j] (dotted-key line 0)]
    (when (and (< j (count line)) (= \= (.charAt line j)))
      {:parts parts :value-at (inc j)})))

(defn- parse-header
  "`{:parts [...] :array? bool}` for a `[table]` or `[[array]]` header line,
   quoted parts unquoted and a trailing comment allowed, or nil."
  [^String line]
  (let [n (count line)
        i (ws-end line 0)]
    (when (and (< i n) (= \[ (.charAt line i)))
      (let [array? (and (< (inc i) n) (= \[ (.charAt line (inc i))))
            close (if array? "]]" "]")]
        (when-let [[parts j] (dotted-key line (+ i (if array? 2 1)))]
          (when (str/starts-with? (subs line j) close)
            (let [k (ws-end line (+ j (count close)))]
              (when (or (= k n) (= \# (.charAt line k)))
                {:parts parts :array? array?}))))))))

(defn- scan-line
  "Bracket depth and open multi-line string (its closing delimiter, or nil)
   after reading `l` from column `col`."
  [^String l col depth ml]
  (let [n (count l)]
    (loop [j col depth depth ml ml]
      (cond
        (>= j n) [depth ml]
        ml (if-let [e (str/index-of l ml j)] (recur (+ e 3) depth nil) [depth ml])
        :else
        (let [c (.charAt l j)]
          (cond
            (= c \#) [depth nil]
            (#{\" \'} c) (if-let [e (skip-string l j)]
                           (recur e depth nil)
                           (if (and (< (+ j 2) n) (= c (.charAt l (+ j 1))) (= c (.charAt l (+ j 2))))
                             [depth (str c c c)]
                             [depth nil]))
            (#{\[ \{} c) (recur (inc j) (inc depth) nil)
            (#{\] \}} c) (recur (inc j) (dec depth) nil)
            :else (recur (inc j) depth nil)))))))

(defn- value-end
  "Index of the line on which the value starting at line `i`, column `col`,
   ends. An array, inline table or multi-line string can run over several
   lines (Codex writes `status_line` one item per line), and rewriting only
   the first of them would leave the rest behind as broken TOML."
  [lines i col]
  (loop [li i col col depth 0 ml nil]
    (if (>= li (count lines))
      (dec (count lines))
      (let [[depth ml] (scan-line (nth lines li) col depth ml)]
        (if (or ml (pos? depth))
          (recur (inc li) 0 depth ml)
          li)))))

(defn segments
  "Split TOML text into [{:header nil|\"a.b\" :lines [...]}], preserving
   everything. `:header` is the table path as `header-for` spells it, so a
   quoted and a bare spelling of one name compare equal; an `[[array]]`
   segment is marked `:array?` and never matches a table. A key's whole value
   is read past before the next header is looked for: a `[rules]` line inside
   a multi-line string or array belongs to that value, not to a new table."
  [text]
  (let [lines (str/split (or text "") #"\n" -1)
        n (count lines)]
    (loop [i 0 acc [{:header nil :lines []}]]
      (if (>= i n)
        acc
        (let [line (nth lines i)]
          (if-let [{:keys [parts array?]} (parse-header line)]
            (recur (inc i) (conj acc (cond-> {:header (header-for parts) :lines [line]}
                                       array? (assoc :array? true))))
            (let [e (if-let [{:keys [value-at]} (parse-key line)] (value-end lines i value-at) i)]
              (recur (inc e) (update-in acc [(dec (count acc)) :lines] into (subvec lines i (inc e)))))))))))

(defn render [segs]
  (str/join "\n" (mapcat :lines segs)))

(defn- key-spans
  "[start end parts] for every `key = value` in a segment's lines; a
   multi-line value's continuation lines fall inside its span."
  [lines]
  (loop [i 0 acc []]
    (if (>= i (count lines))
      acc
      (if-let [{:keys [parts value-at]} (parse-key (nth lines i))]
        (let [e (value-end lines i value-at)]
          (recur (inc e) (conj acc [i e parts])))
        (recur (inc i) acc)))))

(defn- key-span [k lines]
  (first (filter (fn [[_ _ parts]] (= [(u/kw->str k)] parts)) (key-spans lines))))

(defn- table-seg?
  "Is `s` the segment of the table `header` names? An `[[array]]` element
   never is."
  [s header]
  (and (not (:array? s)) (= (:header s) header)))

(defn- set-in-segment [seg k v]
  (let [new-line (str (quote-key k) " = " (encode-value v))
        {:keys [lines]} seg]
    (if-let [[s e] (key-span k lines)]
      (assoc seg :lines (vec (concat (subvec lines 0 s) [new-line] (subvec lines (inc e)))))
      ;; insert before trailing blank lines so tables stay visually grouped
      (let [tail (count (take-while str/blank? (reverse lines)))
            at (- (count lines) tail)]
        (assoc seg :lines (vec (concat (subvec lines 0 at) [new-line] (subvec lines at))))))))

(defn set-key
  "Set `k` = `v` inside table `table-path` (a vector of segments; [] = root).
   Returns new text."
  [text table-path k v]
  (let [segs (segments text)
        header (when (seq table-path) (header-for table-path))
        idx (first (keep-indexed (fn [i s] (when (table-seg? s header) i)) segs))]
    (if idx
      (render (update segs idx set-in-segment k v))
      (render (conj (vec segs)
                    {:header header
                     :lines [(str "[" header "]")
                             (str (quote-key k) " = " (encode-value v))
                             ""]})))))

(defn set-keys [text table-path kvs]
  (reduce (fn [t [k v]] (set-key t table-path k v)) text kvs))

(defn- segment-index [segs table-path]
  (let [header (when (seq table-path) (header-for table-path))]
    (first (keep-indexed (fn [i s] (when (table-seg? s header) i)) segs))))

(defn has-key?
  "Does table `table-path` hold `k` as a key of its own (not a sub-table)?"
  [text table-path k]
  (let [segs (segments text)]
    (boolean (some->> (segment-index segs table-path) (nth segs) :lines (key-span k)))))

(defn remove-key [text table-path k]
  (let [segs (segments text)
        header (when (seq table-path) (header-for table-path))]
    (render (mapv (fn [s]
                    (if (table-seg? s header)
                      (update s :lines
                              (fn [lines]
                                (let [gone (set (mapcat (fn [[a b parts]]
                                                          (when (= [(u/kw->str k)] parts) (range a (inc b))))
                                                        (key-spans lines)))]
                                  (vec (keep-indexed (fn [i l] (when-not (gone i) l)) lines)))))
                      s))
                  segs))))

(defn remove-table-if-empty
  "Drop the table at `table-path` once it holds no key and no comment — the
   bare header a key-by-key prune leaves behind, which for some tables (a
   Codex preset slot) is itself a load error. Sub-tables are separate
   segments and stay."
  [text table-path]
  (if (empty? table-path)
    text
    (let [header (header-for table-path)
          out (render (vec (remove (fn [s] (and (table-seg? s header)
                                                (every? str/blank? (rest (:lines s)))))
                                   (segments text))))]
      (if (and (str/ends-with? text "\n") (seq out) (not (str/ends-with? out "\n")))
        (str out "\n")
        out))))

(defn remove-table
  "Drop a table and every sub-table beneath it."
  [text table-path]
  (let [header (header-for table-path)
        prefix (str header ".")]
    (render (vec (remove (fn [s]
                           (and (:header s)
                                (or (= (:header s) header)
                                    (str/starts-with? (:header s) prefix))))
                         (segments text))))))

(def ^:private py-check
  "import sys,tomllib;tomllib.loads(sys.stdin.read())")

(defn parse-error
  "Why `text` is not valid TOML, or nil when it is. Throws when there is no
   interpreter to check with."
  [text]
  (let [{:keys [exit err]} (run-python (python!) py-check text)]
    (when-not (zero? exit)
      (or (last (remove str/blank? (str/split-lines (str err)))) "invalid TOML"))))

(defonce ^:private unchecked-files (atom #{}))

(defn update-file!
  "Apply `f` to the file text (or \"\" when absent) and write it back. An
   edit that breaks a file which parsed before is refused: these files are
   the CLIs' own, and a broken one stops the CLI from starting. A file that
   did not parse to begin with — Codex reads TOML 1.1, a multi-line inline
   table, which tomllib before python 3.15 rejects — cannot be checked; the
   edit is written, and the reason said once."
  [path f]
  (let [text (or (u/slurp-safe path) "")
        out (f text)]
    (when (not= text out)
      (when-let [e (parse-error out)]
        (if-let [e0 (parse-error text)]
          (when-not (contains? @unchecked-files path)
            (swap! unchecked-files conj path)
            (binding [*out* *err*]
              (println (str "warning: " (u/tilde path) " is not TOML 1.0 as this python's tomllib reads it ("
                            e0 "); edit written unchecked"))))
          (throw (ex-info (str "refusing to write " (u/tilde path) ": the edit would leave invalid TOML (" e ")")
                          {:path path})))))
    (u/write-text! path out)))
