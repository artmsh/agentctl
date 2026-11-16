(ns agentctl.edit
  "Surgical edits to agents.edn *text*.

   The GUI's controls change one field at a time, and the file they change is
   hand-written: comments, blank lines and `$name` references are the reason it
   is readable. Re-serializing a parsed config would erase all three, so edits
   go through a zipper and touch only the node named — the same rule the codex
   adapter follows for config.toml.

   An op is {:op :set|:unset|:expand|:rename|:append :path [k …] :value v}.
   A path step is a map key — a keyword, a `:#def` symbol, or a keyed
   section's vector selector — or an index into a vector of entity rows.
   `:append` adds `value` as the last element of the vector at `path`."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]))

;; ---------------------------------------------------------------- reading

(defn parse
  "The buffer as data, *before* `:#def` expansion — a field showing `high`
   because `$effort` resolved to it would be written back as the literal, and
   the binding every other tool shares would be gone."
  [text]
  (z/sexpr (z/of-string (if (str/blank? text) "{}" text))))

;; ---------------------------------------------------------------- writing

(defn- edn-text
  "`v` as the file writes EDN: `pr-str` puts a comma between map entries,
   which nobody types."
  [v]
  (let [join #(str/join " " (map edn-text %))]
    (cond
      (map? v) (str "{" (str/join " " (map (fn [[k x]] (str (edn-text k) " " (edn-text x))) v)) "}")
      (vector? v) (str "[" (join v) "]")
      (set? v) (str "#{" (join v) "}")
      (seq? v) (str "(" (join v) ")")
      :else (binding [*print-length* nil] (pr-str v)))))

(defn- node [v] (p/parse-string (edn-text v)))

(defn- zget-key
  "The key node for `k` in a map."
  [zl k]
  (loop [kn (z/down zl)]
    (when kn
      (if (= k (z/sexpr kn)) kn (recur (some-> kn z/right z/right))))))

(defn- zget
  "The value node for key `k` of a map, or element `k` of a vector. A keyed
   section's key is a vector selector such as `[team \"review\"]`, which
   `z/get` does not find, so map keys are compared as data."
  [zl k]
  (if (z/map? zl)
    (loop [kn (z/down zl)]
      (when kn
        (if (= k (z/sexpr kn))
          (z/right kn)
          (recur (some-> kn z/right z/right)))))
    (z/get zl k)))

(defn- separator
  "How this map already breaks between entries: the line break and indent in
   front of its last entry. Copying it puts a new key on its own line, indented
   like the ones above, and keeps the blank line between top-level sections
   when the file has one. A single-line map answers nil and stays single-line."
  [zmap]
  (loop [cur (z/down* zmap) run "" found nil]
    (if-not cur
      found
      (let [ws? (z/whitespace-or-comment? cur)]
        (recur (z/right* cur)
               (if ws? (str run (z/string cur)) "")
               (if ws? found (or (re-find #"(?:\n[ \t]*)+$" run) found)))))))

(defn- newline-before-key
  "A new key appended to a map that is written one-per-line belongs on its own
   line too; `assoc` alone leaves it trailing the last entry."
  [zmap k sep]
  (if-let [kn (some-> (zget zmap k) z/left)]
    (let [ws (z/left* kn)]
      (z/up (if (and ws (z/whitespace? ws))
              (z/replace* ws (n/whitespace-node sep))
              (z/insert-left* kn (n/whitespace-node sep)))))
    zmap))

(defn- zassoc [zmap k v]
  (if-let [old (zget zmap k)]
    (z/up (z/replace old (node v)))
    (let [sep (separator zmap)]
      (cond-> (z/assoc zmap k (node v))
        sep (newline-before-key k sep)))))

(defn- coll-node? [zl] (or (z/map? zl) (z/vector? zl)))

(defn- zset-in [zl [k & ks] v]
  (if (empty? ks)
    (if (z/vector? zl)
      (if (and (int? k) (< -1 k (count (z/sexpr zl))))
        (z/assoc zl k (node v))
        (throw (ex-info (str "no row " k " to set") {})))
      (zassoc zl k v))
    (if-let [sub (and (or (z/map? zl) (int? k)) (zget zl k))]
      (if (coll-node? sub)
        (z/up (zset-in sub ks v))
        ;; the node in the way is a scalar — a shorthand spelling the caller
        ;; wanted to keep would have been expanded before this op
        (z/up (zset-in (z/replace sub {}) ks v)))
      (zassoc zl k (assoc-in {} ks v)))))

(defn- znav
  "The node at `path`, or nil if the way there is not a map."
  [zl path]
  (reduce (fn [z k] (when (and z (or (z/map? z) (and (z/vector? z) (int? k)))) (zget z k))) zl path))

(defn- top
  "Back to the outermost *form*. `z/up` does not stop there: above it sits the
   :forms node the whole file is wrapped in, and a lookup on that is a crash."
  [zl]
  (let [u (z/up zl)]
    (if (and u (not= :forms (z/tag u))) (recur u) zl)))

(defn- zunset-in [zl path]
  (let [ks (butlast path)
        k (last path)]
    (loop [cur zl ks (seq ks)]
      (cond
        ks (if-let [sub (when (or (z/map? cur) (int? (first ks))) (zget cur (first ks)))]
             (if (coll-node? sub) (recur sub (next ks)) zl)
             zl)
        ;; a row of a vector is one node; a map entry is a key and its value
        (z/vector? cur) (if (and (int? k) (< -1 k (count (z/sexpr cur))))
                          (top (z/remove (zget cur k)))
                          zl)
        (nil? (zget cur k)) zl
        ;; the value goes first; `z/remove` then leaves the cursor at the
        ;; node before it, which for a vector key is inside the key, so the
        ;; key is found again from the top
        :else (let [root (top (z/remove (zget cur k)))
                    parent (if (seq (butlast path)) (znav root (vec (butlast path))) root)]
                (top (z/remove (zget-key parent k))))))))

(defn- zappend
  "Add `v` as the last row of the vector at `path`, broken and indented like
   the rows above it; a missing vector is created holding just `v`."
  [zl path v]
  (let [zv (znav zl path)]
    (cond
      (nil? zv) (top (zset-in zl path [v]))
      (not (z/vector? zv)) (throw (ex-info (str (pr-str (last path)) " is not a vector of rows") {}))
      (nil? (z/down zv)) (top (z/append-child zv (node v)))
      :else (let [sep (or (separator zv) " ")]
              (top (-> zv
                       (z/append-child* (n/whitespace-node sep))
                       (z/append-child* (node v))))))))

(defn- zrename
  "Rename the key at `path`, leaving its value, its position in the map and the
   whitespace around it alone — a remove-and-add would send a binding to the
   bottom of the map and drop the comment above it.

   Renaming onto a name the map already holds is refused here and not only in
   the browser: the buffer would come back as a map with a duplicate key, which
   is not readable, and the pane that reported the problem would be the one
   that could no longer render."
  [zl path new-key]
  (let [parent-path (vec (butlast path))
        k (last path)
        parent (if (seq parent-path) (znav zl parent-path) zl)]
    (cond
      (or (nil? parent) (not (z/map? parent)))
      (throw (ex-info (str "nothing to rename at " (pr-str (vec path))) {}))

      (nil? (zget parent k))
      (throw (ex-info (str (pr-str k) " is not declared") {}))

      (= k new-key) zl

      (some? (zget parent new-key))
      (throw (ex-info (str (pr-str new-key) " is already declared here") {}))

      :else (top (z/replace (z/left (zget parent k)) new-key)))))

(defn apply-ops
  "Run ops against `text`, returning the new text. Throws on unparseable text —
   the caller answers that with a message, not a write."
  [text ops]
  (let [zl (z/of-string (if (str/blank? text) "{}" text))]
    (when-not (z/map? zl)
      (throw (ex-info "agents.edn must contain a map" {})))
    (-> (reduce (fn [acc {:keys [op path value]}]
                  (let [path (vec path)]
                    (when (empty? path)
                      (throw (ex-info "an edit needs a path" {})))
                    (case (keyword op)
                      :set (top (zset-in acc path value))
                      :unset (zunset-in acc path)
                      ;; idempotent by design: the controls re-send it for as
                      ;; long as their copy of the form says the node is still
                      ;; shorthand, and a second one must not undo the first
                      :expand (if (some-> (znav acc path) z/map?)
                                acc
                                (top (zset-in acc path value)))
                      :rename (zrename acc path value)
                      :append (zappend acc path value)
                      (throw (ex-info (str "unknown edit op " op) {})))))
                zl ops)
        z/root-string)))
