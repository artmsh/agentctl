(ns agentctl.toml
  "Minimal TOML support.

   Reading goes through python3's stdlib tomllib (exact, no hand-rolled parser).
   Writing is deliberately *surgical*: the file is split into header-delimited
   segments and only the lines we own are rewritten, so comments, ordering and
   tool-written state (codex [hooks.state], [projects.*]) survive untouched."
  (:require [agentctl.util :as u]
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

(defn- key-line? [k line]
  (re-matches (re-pattern (str "^\\s*(?:" (java.util.regex.Pattern/quote (u/kw->str k))
                               "|\"" (java.util.regex.Pattern/quote (u/kw->str k)) "\")\\s*=.*$"))
              line))

(defn- set-in-segment [seg k v]
  (let [new-line (str (quote-key k) " = " (encode-value v))
        {:keys [lines]} seg
        idx (first (keep-indexed (fn [i l] (when (key-line? k l) i)) lines))]
    (if idx
      (assoc-in seg [:lines idx] new-line)
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

(defn remove-key [text table-path k]
  (let [segs (segments text)
        header (when (seq table-path) (header-for table-path))]
    (render (mapv (fn [s]
                    (if (= (:header s) header)
                      (update s :lines #(vec (remove (partial key-line? k) %)))
                      s))
                  segs))))

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

(defn update-file!
  "Apply `f` to the file text (or \"\" when absent) and write it back."
  [path f]
  (let [text (or (u/slurp-safe path) "")]
    (u/write-text! path (f text))))
