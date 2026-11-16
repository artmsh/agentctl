(ns agentctl.toml
  "Minimal TOML support.

   Reading goes through python3's stdlib tomllib (exact, no hand-rolled parser).
   Writing is deliberately *surgical*: the file is split into header-delimited
   segments and only the lines we own are rewritten, so comments, ordering and
   tool-written state (codex [hooks.state], [projects.*]) survive untouched."
  (:require [agentctl.util :as u]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- read

(def ^:private py-read
  "import sys,tomllib,json;print(json.dumps(tomllib.load(open(sys.argv[1],'rb'))))")

(defn read-toml
  "Parse a TOML file into a keywordless nested map. Returns nil when absent/unparseable."
  [path]
  (when (u/exists? path)
    (let [{:keys [exit out err]} (u/sh "python3" "-c" py-read (u/abs-path path))]
      (if (zero? exit)
        (json/parse-string out)
        (throw (ex-info (str "TOML parse failed: " path) {:err err}))))))

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

(defn- header-line? [line]
  (re-matches #"^\s*\[\[?[^\]]+\]\]?\s*$" line))

(defn- header-name [line]
  (some-> (re-find #"^\s*\[\[?([^\]]+)\]\]?\s*$" line) second str/trim))

(defn segments
  "Split TOML text into [{:header nil|\"a.b\" :lines [...]}], preserving everything."
  [text]
  (let [lines (str/split (or text "") #"\n" -1)]
    (reduce (fn [acc line]
              (if (header-line? line)
                (conj acc {:header (header-name line) :lines [line]})
                (update-in acc [(dec (count acc)) :lines] conj line)))
            [{:header nil :lines []}]
            lines)))

(defn render [segs]
  (str/join "\n" (mapcat :lines segs)))

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

(defn- parse-key
  "The dotted key a `key = value` line starts with, as unquoted parts, and
   the column just past its `=`. nil for anything else: a header, a comment,
   a blank line, or a line that continues the previous value."
  [^String line]
  (let [n (count line)
        ws (fn [i] (loop [i i] (if (and (< i n) (Character/isWhitespace (.charAt line i))) (recur (inc i)) i)))]
    (loop [i (ws 0) parts []]
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
            (let [j (ws j)]
              (when (< j n)
                (case (.charAt line j)
                  \. (recur (ws (inc j)) (conj parts part))
                  \= {:parts (conj parts part) :value-at (inc j)}
                  nil)))))))))

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
        idx (first (keep-indexed (fn [i s] (when (= (:header s) header) i)) segs))]
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
    (first (keep-indexed (fn [i s] (when (= (:header s) header) i)) segs))))

(defn has-key?
  "Does table `table-path` hold `k` as a key of its own (not a sub-table)?"
  [text table-path k]
  (let [segs (segments text)]
    (boolean (some->> (segment-index segs table-path) (nth segs) :lines (key-span k)))))

(defn ensure-table
  "Append an empty `[table-path]` unless that table already exists."
  [text table-path]
  (let [segs (segments text)]
    (if (segment-index segs table-path)
      text
      (render (conj (vec segs) {:header (header-for table-path)
                                :lines [(str "[" (header-for table-path) "]") ""]})))))

(defn set-tree
  "`set-keys` for a value that nests: a map goes into a sub-table of its
   own — unless the file already holds that key as an inline table, which is
   then rewritten where it stands. Either way the representation on disk is
   kept, so a write never defines one table twice."
  [text table-path m]
  (reduce (fn [t [k v]]
            (if (and (map? v) (not (has-key? t table-path k)))
              (let [sub (conj (vec table-path) (u/kw->str k))]
                (if (empty? v) (ensure-table t sub) (set-tree t sub v)))
              (set-key t table-path k v)))
          text m))

(defn remove-key [text table-path k]
  (let [segs (segments text)
        header (when (seq table-path) (header-for table-path))]
    (render (mapv (fn [s]
                    (if (= (:header s) header)
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
          out (render (vec (remove (fn [s] (and (= (:header s) header)
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
  "Why `text` is not valid TOML, or nil when it is."
  [text]
  (let [{:keys [exit err]} @(p/process ["python3" "-c" py-check] {:in text :out :string :err :string})]
    (when-not (zero? exit)
      (or (last (remove str/blank? (str/split-lines (str err)))) "invalid TOML"))))

(defn update-file!
  "Apply `f` to the file text (or \"\" when absent) and write it back. A
   result that no longer parses is refused rather than written: these files
   are the CLIs' own, and a broken one stops the CLI from starting."
  [path f]
  (let [text (or (u/slurp-safe path) "")
        out (f text)]
    (when-let [e (and (not= text out) (parse-error out))]
      (throw (ex-info (str "refusing to write " (u/tilde path) ": the edit would leave invalid TOML (" e ")")
                      {:path path})))
    (u/write-text! path out)))
