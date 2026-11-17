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

(defn- python-version [py]
  (let [{:keys [exit out]} (run-python py "import sys;print('%d.%d' % sys.version_info[:2])" "")]
    (if (zero? exit) (str/trim out) "?")))

(defn read-toml
  "Parse a TOML file into a keywordless nested map. nil when absent; throws
   when there is no interpreter, and when the file does not parse — with
   the cause and the fix, because Codex reads TOML 1.1 (a multi-line inline
   table), which tomllib reads only from python 3.15. Read as empty instead,
   the plan shows what exists as new and apply never converges."
  [path]
  (when (u/exists? path)
    (try (parse-text (u/slurp-safe path))
         (catch clojure.lang.ExceptionInfo e
           (if (no-python? e)
             (throw e)
             (let [py (python!)]
               (throw (ex-info (str (u/tilde path) " does not parse with " py " " (python-version py)
                                    " (" (str/replace (ex-message e) #"^TOML parse failed: " "") ")."
                                    " Codex also reads TOML 1.1, which tomllib reads only from python 3.15:"
                                    " point AGENTCTL_PYTHON at one, or rewrite that construct as TOML 1.0")
                               (assoc (ex-data e) :type ::unparseable :path path)))))))))

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

(defn- trailing-comment
  "The whitespace and `# comment` after the value starting at `col` of a
   one-line `key = value`, or \"\". A `#` inside a string is not one."
  [^String line col]
  (let [n (count line)
        at (loop [j col]
             (cond
               (>= j n) nil
               (= \# (.charAt line j)) j
               (#{\" \'} (.charAt line j)) (when-let [e (skip-string line j)] (recur e))
               :else (recur (inc j))))]
    (if at
      (subs line (+ col (count (str/trimr (subs line col at)))))
      "")))

(defn- array-in-place
  "The lines of a multi-line array `lines` (`key = [` … `]`, one element a
   line) holding `v` instead, written the same way: the opening and closing
   lines stay as they are, and an element `v` still holds keeps its comment —
   on its line, and on the lines just above it. nil when the old value is
   not written one element a line, or `v` is not a vector of plain values:
   the caller then writes it on one line."
  [lines v]
  (let [first-line (first lines)
        {:keys [value-at]} (parse-key first-line)
        opening (str/trim (subs first-line value-at))
        closing (last lines)
        body (subvec lines 1 (dec (count lines)))
        element (fn [^String l]
                  (let [c (trailing-comment l 0)
                        t (str/trim (subs l 0 (- (count l) (count c))))]
                    (when-not (str/blank? t)
                      (when (str/ends-with? t ",") (str/trim (subs t 0 (dec (count t))))))))]
    (when (and (vector? v) (every? #(or (string? %) (number? %) (boolean? %) (keyword? %)) v)
               (> (count lines) 2)
               (or (= "[" opening) (str/starts-with? opening "[ #") (str/starts-with? opening "[#"))
               (str/starts-with? (str/triml closing) "]")
               ;; every body line is one element with a trailing comma, a
               ;; comment, or blank
               (every? #(or (str/blank? %) (str/starts-with? (str/triml %) "#")
                            (when-let [t (element %)] (not (str/includes? t ","))))
                       body))
      (let [indent (or (some #(when (element %) (re-find #"^\s*" %)) body) "  ")
            ;; each element's text, with the comment lines just above it
            ;; and the comment after it
            kept (loop [ls body above [] acc {}]
                   (if-let [l (first ls)]
                     (if-let [t (element l)]
                       (recur (rest ls) [] (assoc acc t {:above above :after (trailing-comment l 0)}))
                       (recur (rest ls) (if (str/blank? l) [] (conj above l)) acc))
                     acc))
            ;; comments under the last element, before `]`
            tail (vec (reverse (take-while #(str/starts-with? (str/triml %) "#")
                                           (reverse (remove str/blank? body)))))]
        (vec (concat [first-line]
                     (mapcat (fn [x]
                               (let [t (encode-value x)
                                     {:keys [above after]} (get kept t)]
                                 (concat above [(str indent t "," (str/replace (or after "") #"^(?=#)" " "))])))
                             v)
                     tail
                     [closing]))))))

(defn- set-in-segment [seg k v]
  (let [new-line (str (quote-key k) " = " (encode-value v))
        {:keys [lines]} seg]
    (if-let [[s e] (key-span k lines)]
      ;; a same-line comment is the reader's note on the key: it stays, and
      ;; so do an array's when it is written one element a line
      (let [new-lines (if (= s e)
                        [(str new-line (trailing-comment (nth lines s) (:value-at (parse-key (nth lines s)))))]
                        (or (array-in-place (subvec lines s (inc e)) v) [new-line]))]
        (assoc seg :lines (vec (concat (subvec lines 0 s) new-lines (subvec lines (inc e))))))
      ;; insert before trailing blank lines so tables stay visually grouped
      (let [tail (count (take-while str/blank? (reverse lines)))
            at (- (count lines) tail)]
        (assoc seg :lines (vec (concat (subvec lines 0 at) [new-line] (subvec lines at))))))))

(defn- segment-index [segs table-path]
  (let [header (when (seq table-path) (header-for table-path))]
    (first (keep-indexed (fn [i s] (when (table-seg? s header) i)) segs))))

(defn- locate
  "Where the table `table-path` is defined in `segs`: `{:seg i}` for a
   `[header]` table (the root is segment 0); `{:seg i :key k :inner [...]}`
   for an inline table held as key `k` of the header table in segment `i`,
   `inner` descending through inline tables nested in it; nil when the file
   does not define it yet. A table defined by dotted keys (`a.b = 1`) is not
   found, and a header written for it is refused by `update-file!`."
  [segs table-path]
  (let [tp (mapv u/kw->str table-path)]
    (if-let [i (segment-index segs tp)]
      {:seg i}
      (some (fn [n]
              (when-let [i (segment-index segs (subvec tp 0 n))]
                (when (key-span (nth tp n) (:lines (nth segs i)))
                  {:seg i :key (nth tp n) :inner (subvec tp (inc n))})))
            (range (dec (count tp)) -1 -1)))))

(defn- dissoc-pruning
  "`m` without `k` under `path`, dropping each map the removal empties."
  [m path k]
  (if (empty? path)
    (dissoc m k)
    (let [sub (get m (first path))]
      (if (map? sub)
        (let [sub' (dissoc-pruning sub (rest path) k)]
          (if (empty? sub') (dissoc m (first path)) (assoc m (first path) sub')))
        m))))

(defn- edit-inline
  "Rewrite the inline table held as `k` in `seg` through `f`, where it
   stands. Its other entries are kept: they are the ones written by hand. A
   key holding anything but a table is refused rather than overwritten, and
   a table `f` empties goes with its key, as a pruned `[table]` does."
  [seg k f]
  (let [lines (:lines seg)
        [s e] (key-span k lines)
        {:keys [value-at]} (parse-key (nth lines s))
        v (get (parse-text (str/join "\n" (cons (str "v = " (subs (nth lines s) value-at))
                                                (subvec lines (inc s) (inc e)))))
               "v")]
    (when-not (map? v)
      (throw (ex-info (str "`" k "` holds " (if (sequential? v) "an array" "a value")
                           ", not a table — left as it is")
                      {:key k})))
    (let [v' (f v)]
      (if (= v' v)
        seg
        (assoc seg :lines (vec (concat (subvec lines 0 s)
                                       (when (seq v') [(str (quote-key k) " = " (encode-value v'))])
                                       (subvec lines (inc e)))))))))

(defn set-key
  "Set `k` = `v` inside table `table-path` (a vector of segments; [] = root).
   The table may be a `[header]` table or an inline one (`domains = {…}`),
   which is edited in place; one the file lacks gets a header of its own.
   Returns new text."
  [text table-path k v]
  (let [segs (segments text)
        {:keys [seg key inner] :as loc} (locate segs table-path)]
    (cond
      key (render (update segs seg edit-inline key #(assoc-in % (conj inner (u/kw->str k)) v)))
      loc (render (update segs seg set-in-segment k v))
      :else (let [header (header-for table-path)]
              (render (conj (vec segs)
                            {:header header
                             :lines [(str "[" header "]")
                                     (str (quote-key k) " = " (encode-value v))
                                     ""]}))))))

(defn set-keys [text table-path kvs]
  (reduce (fn [t [k v]] (set-key t table-path k v)) text kvs))

(declare remove-key)

(defn set-table
  "Make the `[header]` table `table-path` hold exactly `kvs`, where it
   stands: keys it has beyond them go, and the rest is `set-keys`."
  [text table-path kvs]
  (let [segs (segments text)
        loc (locate segs table-path)
        wanted (set (map (comp u/kw->str first) kvs))
        extra (when (and loc (not (:key loc)))
                (remove wanted (keep (fn [[_ _ parts]] (when (= 1 (count parts)) (first parts)))
                                     (key-spans (:lines (nth segs (:seg loc)))))))]
    (set-keys (reduce #(remove-key %1 table-path %2) text extra) table-path kvs)))

(defn remove-key
  "Remove `k` from table `table-path`, a `[header]` or an inline table."
  [text table-path k]
  (let [segs (segments text)
        {:keys [seg key inner] :as loc} (locate segs table-path)]
    (cond
      key (render (update segs seg edit-inline key #(dissoc-pruning % inner (u/kw->str k))))
      loc (render (update segs seg update :lines
                          (fn [lines]
                            (let [gone (set (mapcat (fn [[a b parts]]
                                                      (when (= [(u/kw->str k)] parts) (range a (inc b))))
                                                    (key-spans lines)))]
                              (vec (keep-indexed (fn [i l] (when-not (gone i) l)) lines))))))
      :else text)))

(defn- keep-final-newline
  "`out`, ending in a newline when `text` did: dropping the last table takes
   the blank line that ended the one before it."
  [text out]
  (if (and (str/ends-with? text "\n") (seq out) (not (str/ends-with? out "\n")))
    (str out "\n")
    out))

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
      (keep-final-newline text out))))

(defn remove-table
  "Drop a table and every sub-table beneath it."
  [text table-path]
  (let [header (header-for table-path)
        prefix (str header ".")]
    (keep-final-newline
     text
     (render (vec (remove (fn [s]
                            (and (:header s)
                                 (or (= (:header s) header)
                                     (str/starts-with? (:header s) prefix))))
                          (segments text)))))))

(defn replace-table
  "Make the table `table-path` hold exactly `m`, edited where it stands:
   keys it has beyond `m` go, keys `m` keeps keep their lines and comments,
   and a map `m` holds where the file has a `[sub-table]` is replaced the same
   way, there. A sub-table `m` does not hold as one goes. A table the file
   lacks, or holds inline, is written anew."
  [text table-path m]
  (let [tp (mapv u/kw->str table-path)
        segs (segments text)
        loc (locate segs tp)]
    (if (or (nil? loc) (:key loc))
      (set-keys (remove-table text tp) tp m)
      (let [header-of #(header-for (conj tp (u/kw->str %)))
            sub? (fn [k] (some? (segment-index segs (conj tp (u/kw->str k)))))
            as-table (into {} (filter (fn [[k v]] (and (map? v) (sub? k)))) m)
            prefix (str (header-for tp) ".")
            kept-prefixes (map #(header-of (key %)) as-table)
            text (keep-final-newline
                  text
                  (render (vec (remove (fn [sg]
                                         (let [h (:header sg)]
                                           (and h (str/starts-with? h prefix)
                                                (not-any? #(or (= h %) (str/starts-with? h (str % "."))) kept-prefixes))))
                                       segs))))
            text (set-table text tp (vec (remove (comp (set (keys as-table)) key) m)))]
        (reduce (fn [t [k v]] (replace-table t (conj tp (u/kw->str k)) v)) text as-table)))))

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
