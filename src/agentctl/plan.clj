(ns agentctl.plan
  "Operation records, diffing and terraform-style rendering."
  (:require [agentctl.refs :as refs]
            [agentctl.toml :as toml]
            [cheshire.core :as json]
            [agentctl.util :as u]
            [clojure.string :as str]))

(def ^:const version 1)

;; ---------------------------------------------------------------- ops

(defn op
  "Build an operation. `:exec!` is a thunk run only by apply!."
  [m]
  (merge {:action :noop :risk :low} m))

(defn mutating? [o] (contains? #{:create :update :delete} (:action o)))

(defn sigil [action]
  (case action :create "+" :update "~" :delete "-" :noop "=" "?"))

(def ^:dynamic *color*
  "Emit ANSI colour? nil asks the environment; false is how the GUI gets plain
   text it can colour itself."
  nil)

(defn- colorize [action s]
  (let [code (case action :create "32" :update "33" :delete "31" :noop "90" "0")]
    (if (if (some? *color*) *color* (nil? (System/getenv "NO_COLOR")))
      (str "\033[" code "m" s "\033[0m")
      s)))

(defn- render-val
  "The key comes along so a credential is masked: a plan is read out loud, put
   in a ticket and pasted into chat — it is not a place for a live token."
  ([v] (render-val nil v))
  ([k v]
   (cond
     (refs/ref? v) (refs/describe v)
     (nil? v) "∅"
     (refs/masked? v) (pr-str v)
     (or (refs/credential? k v) (refs/resolved-secret? v)) (pr-str (refs/mask v))
     (coll? v) (pr-str (u/norm (refs/scrub v)))
     (string? v) (pr-str v)
     :else (pr-str v))))

(defn field-diffs
  "Field-level diff between two maps, restricted to `ks` when given."
  ([before after] (field-diffs before after (distinct (concat (keys before) (keys after)))))
  ([before after ks]
   (for [k ks
         :let [b (get before k) a (get after k)]
         :when (not= (u/norm b) (u/norm a))]
     {:key k :before b :after a})))

(defn- map-fields
  "The fields under a map in a diff. A map created whole lists every key it
   holds, null ones included: a key set to null is a declaration (an override
   that inherits its default), and `field-diffs` — which cannot tell an absent
   key from a null one — would show the new map as empty."
  [b a]
  (if (nil? b)
    (for [[k v] (u/norm (or a {}))] {:key k :before nil :after v})
    (field-diffs (u/norm b) (u/norm (or a {})))))

(defn- diff-action [b a]
  (cond (nil? b) :create (nil? a) :delete :else :update))

(defn- plain-map? [v] (and (map? v) (not (refs/ref? v))))

(defn- shown
  "What a field is called in the plan: a prefixed field's label, else its key."
  [k]
  (if (map? k) (:label k) (u/kw->str k)))

(defn- native-key
  "The key a value is masked by — never the label, whose prefix (a profile
   or a table name) says nothing about whether the value is a credential."
  [k]
  (if (map? k) (:key k) k))

(defn- render-diff
  "One line per changed field, nested. Printing both sides of a map whole makes
   the reader diff it by eye — which is the job this output exists to do."
  [indent k b a]
  (let [act (diff-action b a)
        mk (native-key k)]
    (if (and (or (plain-map? b) (nil? b))
             (or (plain-map? a) (nil? a))
             (or (plain-map? b) (plain-map? a)))
      (cons (colorize act (str indent (sigil act) " " (shown k)))
            (mapcat (fn [{:keys [key before after]}]
                      (render-diff (str indent "  ") key before after))
                    (map-fields b a)))
      [(colorize act
                 (str indent (sigil act) " " (shown k) ": "
                      (case act
                        :create (render-val mk a)
                        :delete (render-val mk b)
                        (str (render-val mk b) " -> " (render-val mk a)))))])))

(def ^:private secret-maps
  "Maps whose every value may be a credential under a name that does not say
   so: an MCP server's environment and its HTTP headers."
  #{"env" "headers" "http_headers" "env_http_headers"})

(defn- plain-value?
  "A value in an env or header map that is no one's secret: a boolean, a
   small number, a common settings word, a `$VAR` reference or a path."
  [v]
  (or (boolean? v)
      (and (number? v) (< -100000 v 100000))
      (and (string? v)
           (or (re-matches #"-?\d{1,5}" v)
               (contains? refs/plain-words (str/lower-case v))
               (re-matches #"\$\{?[A-Za-z_][A-Za-z0-9_]*\}?" v)
               (re-find #"^[/~]" v)))))

(defn- mask-leaf [k v hit?]
  (cond
    (or (nil? v) (refs/ref? v) (refs/masked? v)) v
    (or hit? (refs/credential? k v) (refs/resolved-secret? v)) (refs/mask v)
    (coll? v) (refs/scrub v)
    :else v))

(defn- mask-diff
  "`[before after]` of one diff with every credential masked, as every output
   must show it — text, GUI and JSON alike, so none of them can print what
   another hides. A value is masked when its name says it is a credential,
   when it is what a ref resolved to, or when it is the before side of either
   — a rotated or dropped secret is still one, though nothing resolves it
   any more. Inside an environment or a header map any value that changes or
   goes away is masked on the before side: what it held may have come from a
   ref the declaration no longer makes, and nothing on disk says which."
  ([k b a] (mask-diff k b a false))
  ([k b a secret-map?]
   (let [mk (native-key k)]
     (if (and (or (plain-map? b) (nil? b))
              (or (plain-map? a) (nil? a))
              (or (plain-map? b) (plain-map? a)))
       ;; a side read from JSON is keyed by keywords, a declared one by
       ;; strings, and a merged one by both: keyed by name, as `u/norm`
       ;; compares them, the same field meets itself and is masked once
       (let [inner? (or secret-map? (contains? secret-maps (u/kw->str mk)))
             by-name (fn [m] (when m (into (sorted-map) (map (fn [[kk v]] [(u/key-str kk) v])) m)))
             b (by-name b)
             a (by-name a)
             pairs (into {} (for [kk (distinct (concat (keys b) (keys a)))]
                              [kk (mask-diff kk (get b kk) (get a kk) inner?)]))
             side (fn [m pick] (when m (into (sorted-map) (map (fn [kk] [kk (pick (pairs kk))])) (keys m))))]
         [(side b first) (side a second)])
       (if (and (sequential? b) (sequential? a))
         ;; argv: a credential position is masked on each side whatever it
         ;; holds — a value read back from disk that nothing resolves now, or
         ;; one too short to be searched for — and a rotated secret sits at
         ;; the same position on both sides
         (let [mb (refs/mask-argv b)
               ma (refs/mask-argv a)
               pairs (mapv (fn [i]
                             (let [rb (nth b i nil) ra (nth a i nil)
                                   xb (nth mb i nil) xa (nth ma i nil)
                                   [db da] (mask-diff mk rb ra secret-map?)
                                   pb? (not= rb xb)
                                   pa? (not= ra xa)]
                               (if (or pb? pa?)
                                 (let [b' (if pb? xb db) a' (if pa? xa da)]
                                   [b' (if (and (some? ra) (= b' a') (not= rb ra) (not (refs/masked? ra)))
                                         (str a' " (changed)")
                                         a')])
                                 [db da])))
                           (range (max (count b) (count a))))]
           [(mapv (comp first pairs) (range (count b)))
            (mapv (comp second pairs) (range (count a)))])
       (let [changed? (not= (u/norm b) (u/norm a))
             a-hit? (or (refs/credential? mk a) (refs/resolved-secret? a))
             ;; inside an env or header map an unchanged value is masked
             ;; unless it is plainly no secret: the text plan never prints
             ;; it, but `--json` prints the whole map, and a value kept from
             ;; disk may be a secret nothing declares any more
             kept? (and secret-map? (not changed?))
             a' (mask-leaf mk a (and kept? (not (plain-value? a))))
             b' (mask-leaf mk b (or (and changed? (or a-hit? secret-map?))
                                    (and kept? (not (plain-value? b)))))]
         ;; a rotated secret can mask the same on both sides; the change
         ;; must still show
         [b' (if (and changed? (some? a') (= b' a') (not (refs/masked? a)))
               (str a' " (changed)")
               a')]))))))

(defn mask-op
  "`o` as output may show it: every diff masked by `mask-diff`, and every
   resolved value scrubbed from whatever else it carries. The op that runs is
   untouched — only what is printed, served or serialized goes through here."
  [o]
  (refs/scrub
   (cond-> o
     (seq (:diffs o))
     (update :diffs (partial mapv (fn [{:keys [key before after] :as d}]
                                    (let [[b a] (mask-diff key before after)]
                                      (assoc d :before b :after a))))))))

(defn changed? [before after ks] (boolean (seq (field-diffs before after ks))))

(defn- id-str
  "The id without the project namespace — a header that already says
   `projects/example` should not say it twice."
  [id]
  (if (keyword? id) (name id) (str id)))

(defn- scope-label
  "Where the resource belongs. Only a project is a scope; everything else is
   the user's own machine and needs no saying."
  [o]
  (or (some-> (:project-path o) u/tilde)
      (when-let [p (:project o)] (str "projects/" (u/kw->str p)))))

(defn- dot-prefix
  "The `permissions.` in `permissions.allow` / `permissions.ask` — ids that are
   two halves of one setting read better joined than repeated."
  [ids]
  (let [common (reduce (fn [a b]
                         (subs a 0 (count (take-while true? (map = a b)))))
                       ids)
        i (str/last-index-of common ".")]
    (if i (subs common 0 (inc i)) "")))

(defn- ids-label
  "One id plain, several in braces with anything they share factored out."
  [ids]
  (if (= 1 (count ids))
    (first ids)
    (let [pre (dot-prefix ids)]
      (str pre "{" (str/join " " (map #(subs % (count pre)) ids)) "}"))))

(defn- body-label
  "`mcps/{a b}` — except for `:projects`, whose kind is already spoken by the
   scope that precedes it, and an op named after the project itself, which the
   scope has already said in full."
  [o ids]
  (let [label (ids-label ids)]
    (if (= :projects (:kind o))
      (when-not (= label (some-> (:project o) u/kw->str)) label)
      (str (name (or (:shown-kind o) (:kind o))) "/" label))))

(defn- head-line
  "`~ projects/example mcps/{clj-repl dbx}`. The tool is the section heading
   above, not part of every line under it."
  [action o ids]
  (str/join " " (remove nil? [(sigil action) (scope-label o) (body-label o ids)])))

(defn- op-diffs
  "`[collapsed? diffs]`. A single diff keyed by the resource itself repeats the
   header line — that level is dropped and the fields shown directly, and the
   caller is told, because the fields then need the resource name back."
  [o]
  (let [ds (:diffs o)]
    (if (and (= 1 (count ds))
             (= (u/kw->str (:key (first ds))) (name (:id o)))
             (plain-map? (:after (first ds))))
      (let [{:keys [before after]} (first ds)]
        [true (map-fields before after)])
      [false ds])))

(defn- render-cmd
  "A shell command the plan will run, marked `!` — the one kind of line here
   that is not a file edit, and the one a reader may want to run by hand."
  [argv]
  (str "  !" (str/join " " (map (comp u/tilde str) (remove nil? (flatten argv))))))

(defn- op-cmds [o] (when-let [c (:cmds o)] (if (vector? (first c)) c [c])))

(defn- prefixed
  "A field's label under the op's `:field-prefix` — `presets.5` for a field
   of preset slot 5, which `input` alone does not say. The diff `:key` stays
   the native key: ownership records and prunes by it."
  [o k]
  (if-let [p (:field-prefix o)] {:label (str p "." (u/kw->str k)) :key k} k))

(defn render-op [o {:keys [verbose]}]
  (let [cmds (op-cmds o)
        line (colorize (:action o) (head-line (:action o) o [(id-str (:id o))]))
        detail (when (and (:summary o) (not cmds)) (str "    " (:summary o)))
        note (when (:note o) (str "    " (:note o)))
        ;; `:preview` — an op whose diff *is* the change (a whole file
        ;; written) shows it on create too, not only under -v
        diffs (when (or verbose (= :update (:action o)) (:preview o))
                (mapcat (fn [{:keys [key before after]}]
                          (render-diff "      " (prefixed o key) before after))
                        (second (op-diffs o))))
        target (when (and verbose (:target o)) (str "    target: " (u/tilde (:target o))))]
    (str/join "\n" (remove nil? (concat [line detail note]
                                        (map render-cmd cmds)
                                        [target] diffs)))))

;; ---------------------------------------------------------------- grouping

(defn- quiet-noop?
  "A converged check: the file already says what the config says. It has a name
   and nothing else to report."
  [o]
  (and (= :noop (:action o)) (not (:summary o)) (empty? (:diffs o))))

(defn- groupable?
  "Mutations against one file group, and so do converged checks against one
   file — both are about that file and read as one line. A noop that carries a
   message is a message, and reads wrong folded into someone else's header."
  [o]
  (and (:target o) (or (mutating? o) (quiet-noop? o))))

(defn- group-key
  "One file, one project, one kind — and changes apart from checks, so a block
   headed `~` never turns out to be mostly things that did not change."
  [o]
  (when (groupable? o) [(:kind o) (:project o) (:target o) (quiet-noop? o)]))

(defn- chunk-ops
  "Ops writing one file for one project become a single entry, in the position
   of the first of them; everything else stands alone."
  [ops]
  (let [groups (group-by group-key ops)]
    (:out (reduce (fn [{:keys [seen out] :as acc} o]
                    (let [k (group-key o)
                          g (get groups k)]
                      (cond
                        (nil? k) (update acc :out conj [o])
                        (contains? seen k) acc
                        :else (-> acc (update :seen conj k) (update :out conj g)))))
                  {:seen #{} :out []}
                  ops))))

(defn- target-label
  "The file, said as briefly as it can be said without becoming ambiguous: a
   path inside the project drops the project prefix the header already carries."
  [o]
  (let [t (u/tilde (:target o))
        marker (some->> (:project o) u/kw->str (format "/%s/"))
        i (when marker (str/index-of t marker))]
    (if i (subs t (+ i (count marker))) t)))

(defn- group-action [ops]
  (let [acts (distinct (map :action ops))]
    (if (= 1 (count acts)) (first acts) :update)))

(defn- group-verb [action]
  (case action :create "create" :delete "remove" "edit"))

(defn- diff-label
  "Inside a group the resource name qualifies the field — several ops share the
   header, and `type` alone would not say whose. An op contributing one field of
   its own naming (a settings key, a permissions bucket) already reads clearly."
  [o collapsed? ds]
  (cond
    (:field-prefix o) #(prefixed o %)
    (or collapsed? (> (count ds) 1)) #(str (name (:id o)) "." (u/kw->str %))
    :else #(u/kw->str %)))

(defn render-group [ops opts]
  (if (= 1 (count ops))
    (render-op (first ops) opts)
    (let [action (group-action ops)
          o (first ops)
          head (head-line action o (map #(id-str (:id %)) ops))]
      (str/join
       "\n"
       (concat
        (remove nil?
                [(colorize action head)
                 ;; a converged group changes nothing — naming the file it did
                 ;; not touch, and then listing nothing under it, says less
                 ;; than the header already did
                 (when-not (= :noop action)
                   (str "    " (group-verb action) " `" (target-label o) "`:"))])
        (for [x ops :when (:note x)] (str "      " (name (:id x)) ": " (:note x)))
        (mapcat (fn [x]
                  (let [[collapsed? ds] (op-diffs x)
                        label (diff-label x collapsed? ds)]
                    (cond
                      (quiet-noop? x) nil
                      ;; nothing to diff — a prune carries only a summary, and
                      ;; without this it would contribute a name to the header
                      ;; and then say nothing at all about what happens to it
                      (empty? ds)
                      [(colorize (:action x)
                                 (str "      " (sigil (:action x)) " " (name (:id x))
                                      (when (:summary x) (str ": " (:summary x)))))]

                      :else
                      (mapcat (fn [{:keys [key before after]}]
                                (render-diff "      " (label key) before after))
                              ds))))
                ops))))))

;; ---------------------------------------------------------------- skill blocks

(defn- shell-word [s] (if (re-find #"[\s*?$'\"]" s) (str "\"" s "\"") s))

(defn- scope-heading
  "`USER(login)` for the machine, `PROJECT(projects/x)` for a repository —
   skills are grouped by where they land, not by which tool reads them."
  [path]
  (if path
    (str "PROJECT(" (str/replace (u/tilde path) #"^~/" "") ")")
    (str "USER(" (System/getProperty "user.name") ")")))

(defn- skill-line
  "`+ skill [agents: claude-code, codex] source` /
   `+ skill-pack [agents: …] source [skill: a, b] [alias]`."
  [action {:keys [kind source skills alias]} agents]
  (str/join " " (remove nil? [(sigil action) kind
                              (when (seq agents) (str "[agents: " (str/join ", " agents) "]"))
                              source
                              (when (and (= kind "skill-pack") (seq skills))
                                (str "[skill: " (str/join ", " skills) "]"))
                              (when alias (str "[" alias "]"))])))

(defn- link-line
  "What one link op changes, for `--verbose`: where the link is and what it
   points at, before and after. It starts with a word, not a path: a line
   whose first character is `~` would read as a change sigil."
  [o]
  (for [{:keys [key before after]} (:diffs o)
        :when (= :target key)]
    (str "    link " (u/tilde (:target o)) " -> " (u/tilde (str after))
         (when before (str " (was " (u/tilde (str before)) ")")))))

(defn- render-skill-blocks
  "One line per entity, action and source in each scope, however many tools
   it reaches; a project line ends with the commands it runs, and with
   `--verbose` every line does, followed by what each link points at. Two
   tools linking one skill from different places are two lines: folded into
   one, the line would name only the first one's source."
  [ops {:keys [verbose]}]
  (let [placed (mapcat (fn [o]
                         (let [ps (get-in o [:display :in-projects])]
                           (if (and (seq ps) (not (:project-path o)))
                             (map #(assoc o :project-path %) ps)
                             [o])))
                       ops)
        ;; a pending report is redundant beside a change to the same entity
        changing (set (for [o placed :when (mutating? o)]
                        [(:project-path o) (get-in o [:display :entity])]))
        placed (remove #(and (:pending %)
                             (changing [(:project-path %) (get-in % [:display :entity])]))
                       placed)
        by-scope (group-by :project-path placed)]
    (for [path (cons nil (sort (remove nil? (keys by-scope))))
          :let [scope-ops (get by-scope path)]
          :when (seq scope-ops)
          s (cons (str "\n" (scope-heading path))
                  (for [[[_ _ action warn] g] (sort-by (fn [[[k e a _ s] _]] [(str e) k (str a) (str s)])
                                                       (group-by (fn [o] [(get-in o [:display :kind])
                                                                          (get-in o [:display :entity])
                                                                          (:action o)
                                                                          (when (:warn o) (:summary o))
                                                                          (get-in o [:display :source])])
                                                                 scope-ops))
                        :let [d (:display (first g))
                              agents (sort (distinct (concat (:agents d) (keep #(get-in % [:display :agent]) g))))
                              skills (sort (distinct (mapcat #(get-in % [:display :skills]) g)))
                              cmds (distinct (mapcat op-cmds g))]]
                    (str/join "\n" (concat [(colorize action (skill-line action (assoc d :skills skills) agents))]
                                            (when warn [(str "    " warn)])
                                            ;; what a change does beyond its line, such as replacing a
                                            ;; file agentctl did not write
                                            (map #(str "    ⚠ " %) (distinct (keep :note g)))
                                            (when (or path verbose)
                                              (map #(str "exec: '" (str/join " " (map (comp shell-word u/tilde str) (remove nil? (flatten %)))) "'")
                                                   cmds))
                                            (when verbose (mapcat link-line g))))))]
      s)))

(defn show-plan?
  "Is there a plan to print, or only \"no changes\"? A warning is: it says a
   declared setting went nowhere, and that holds on every run, converged or
   not — behind \"no changes\" it would make the config read as applied."
  [ops {:keys [show-noop]}]
  (boolean (or (some mutating? ops) show-noop (some :warn ops))))

(declare render-plan*)

(defn render-plan
  "The plan as text. Every op is masked first (`mask-op`) and the text
   scrubbed after: no output mode may print a value another one hides."
  [ops opts]
  (refs/scrub (render-plan* (map mask-op ops) opts)))

(defn- render-plan*
  [ops {:keys [show-noop] :as opts}]
  ;; a warning noop is the report that a declared setting went nowhere — hiding
  ;; it would make the config look applied when it was not. A converged check is
  ;; the opposite report, and just as worth having: the setting is declared, it
  ;; was looked at, and the file already agrees
  (let [visible (if show-noop
                  ops
                  (filter #(or (mutating? %) (:warn %) (quiet-noop? %)) ops))
        {skill-ops true visible false} (group-by #(boolean (:display %)) visible)
        by-tool (group-by :tool visible)]
    (str/join
     "\n"
     (concat
      (render-skill-blocks skill-ops opts)
      (for [tool (sort-by name (keys by-tool))
            :let [tool-ops (sort-by (juxt (comp name :kind) (comp u/kw->str :id)) (get by-tool tool))]
            s (cons (str "\n" (str/upper-case (name tool)))
                    (map #(render-group % opts) (chunk-ops tool-ops)))]
        s)))))

;; ------------------------------------------------- structured plan (the GUI)

;; The GUI renders the same plan as a table rather than a block of text, and
;; needs the parts separately to do it. What it must *not* get is `:diffs`:
;; `render-val` is where a credential read out of an existing config file is
;; masked, so every value here goes through it, exactly as the text does.

(def ^:private container-suffixes
  {".json" "json" ".jsonc" "json" ".yaml" "yaml" ".yml" "yaml"
   ".toml" "toml" ".edn" "edn" ".xml" "xml"})

(defn- container-of
  "The structured format of the file an op edits, when it has one. A path with
   no known suffix is a file or a directory in its own right, not a container."
  [target]
  (when target
    (some (fn [[suffix fmt]] (when (str/ends-with? target suffix) fmt))
          container-suffixes)))

(defn category
  "Which lane an op belongs to. Four exist:

     :struct — a value set or removed at a path inside json/yaml/toml/edn
     :fs     — a file or directory created or removed (link, copy, unlink)
     :cmd    — a command run against a tool's own CLI or against git
     :report — nothing changes: a converged check, or a warning that a
               declared setting went nowhere

   Renaming and moving are not in the list because agentctl never does either:
   a resource it no longer wants is removed and the new one created."
  [o]
  (cond
    (:category o) (:category o)
    (= :noop (:action o)) :report
    (:warn o) :report
    (container-of (:target o)) :struct
    (seq (:cmds o)) :cmd
    (:target o) :fs
    :else :cmd))

(defn run
  "One command, as slots and as the argv that runs it.

   A command rendered from a re-parsed argv is a guess; a command rendered
   from slots that nothing checks against the argv is a lie waiting to happen.
   So the caller passes both — built from the same locals — and this asserts
   that every slot it claims actually appears in what will run.

     {:program \"gh\" :action [\"repo\" \"clone\"] :subject \"obra/superpowers\"
      :into root :flags [[\"--branch\" ref]] :argv [\"gh\" \"repo\" ...]}"
  [{:keys [program action subject into flags argv]}]
  (let [strs (mapv str (remove nil? argv))
        present? (fn [v] (or (nil? v) (some #(= (str v) %) strs)))]
    (assert (= program (first strs)) "run: :program is not the argv head")
    (assert (every? present? (concat [subject into] (map second flags)))
            "run: a slot names something the argv does not carry")
    {:program program
     :action (str/join " " (map str action))
     :subject (some-> subject str u/tilde)
     :into (some-> into str u/tilde)
     :flags (vec (for [[k v] flags :when k] {:k (str k) :v (some-> v str u/tilde)}))
     :shell (str/join " " (map u/tilde strs))}))

(defn- diff-rows
  "`render-diff`, as data. Same recursion into nested maps, same masking; the
   indent becomes a depth the table can indent by itself."
  [depth k b a]
  (let [act (diff-action b a)
        mk (native-key k)]
    (if (and (or (plain-map? b) (nil? b))
             (or (plain-map? a) (nil? a))
             (or (plain-map? b) (plain-map? a)))
      (cons {:action (name act) :depth depth :key (shown k)}
            (mapcat (fn [{:keys [key before after]}]
                      (diff-rows (inc depth) key before after))
                    (map-fields b a)))
      [{:action (name act) :depth depth :key (shown k)
        :before (when-not (= :create act) (render-val mk b))
        :after (when-not (= :delete act) (render-val mk a))}])))

(defn- op-rows
  "The rows one op contributes to its group. `label` qualifies a field with the
   resource it came from where the group header does not already say it."
  [o label-fn]
  (let [[collapsed? ds] (op-diffs o)
        label (or label-fn (diff-label o collapsed? ds))]
    (cond
      (quiet-noop? o) nil
      ;; a prune carries only a summary — without this it would contribute a
      ;; name to the header and then say nothing about what happens to it
      (empty? ds) [{:action (name (:action o)) :depth 0
                    :key (u/kw->str (:id o)) :summary (:summary o)}]
      :else (mapcat (fn [{:keys [key before after]}]
                      (diff-rows 0 (label key) before after))
                    ds))))

(defn- group-data [ops]
  (let [o (first ops)
        action (group-action ops)
        ids (mapv #(id-str (:id %)) ops)
        single? (= 1 (count ops))]
    {:action (name action)
     :category (name (category o))
     ;; the group is as risky as its riskiest member: a secret write hidden
     ;; behind a low-risk first op is exactly the thing this label exists for
     :risk (name (or (first (filter (set (map #(:risk % :low) ops))
                                    [:secret :high :medium :low]))
                     :low))
     :scope (scope-label o)
     :kind (some-> (:kind o) name)
     :label (body-label o ids)
     :ids ids
     :target (some-> (:target o) u/tilde)
     :target-short (when (:target o) (target-label o))
     :container (container-of (:target o))
     :path (:path o)
     :entry (:entry o)
     :fs-op (:fs-op o)
     :from (:from o)
     :verb (when-not (= :noop action) (group-verb action))
     :summary (when single? (:summary o))
     :notes (vec (for [x ops :when (:note x)]
                   {:id (u/kw->str (:id x)) :note (:note x)}))
     ;; `:runs` are commands agentctl actually executes, in slots. `:cmds` is
     ;; the flat line — for the fs lane that is only the shell *equivalent* of
     ;; what `exec!` does through `fs`, never a step that runs
     :runs (vec (mapcat :runs ops))
     :cmds (vec (for [x ops, c (op-cmds x)]
                  (str/join " " (map (comp u/tilde str) (remove nil? (flatten c))))))
     :rows (vec (mapcat #(op-rows % (when single? (fn [k] (let [l (prefixed % k)] (if (map? l) l (u/kw->str l)))))) ops))}))

(defn plan-data
  "`render-plan` as data: the same visibility rule, the same tool sections and
   the same grouping — one file, one project, one kind reads as one entry."
  [ops {:keys [show-noop]}]
  (let [visible (if show-noop
                  ops
                  (filter #(or (mutating? %) (:warn %) (quiet-noop? %)) ops))
        by-tool (group-by :tool (map mask-op visible))]
    (refs/scrub
     (vec (for [tool (sort-by name (keys by-tool))
               :let [tool-ops (sort-by (juxt (comp name :kind) (comp u/kw->str :id))
                                       (get by-tool tool))]]
           {:tool (name tool)
            :groups (mapv group-data (chunk-ops tool-ops))})))))

(defn summary-line
  "Counts by action. A resource held back on an unresolved secret is a noop
   that did not converge, so it is counted apart rather than as unchanged."
  [ops]
  (let [held (count (filter :unresolved ops))
        withheld (reduce + 0 (keep :withheld ops))
        f (frequencies (map :action (remove #(or (:unresolved %) (:withheld %)) ops)))]
    (str (format "%d to add, %d to change, %d to remove, %d unchanged"
                 (get f :create 0) (get f :update 0) (get f :delete 0) (get f :noop 0))
         (when (pos? held) (format ", %d unresolved" held))
         (when (pos? withheld) (format ", %d withheld" withheld)))))

(defn withheld-changes
  "Every change the plan withheld, as data: `{:tool :project :action :kind :id :target :keys}`."
  [ops]
  (vec (mapcat :withheld-changes ops)))

(defn withheld?
  "Does the plan hold back changes it could not make (`:withheld`)? It has
   not converged, whatever else it does."
  [ops]
  (boolean (some :withheld ops)))

(defn op-counts
  "The numbers behind the GUI's pills, counted over ops the way `summary-line`
   counts them. Counting the rendered text instead takes every nested field
   line for a create and every per-tool `!` line for a command. `:cmds` is
   one per changing op that runs a shell command, however many lines it
   renders as."
  [ops]
  (let [f (frequencies (map :action ops))]
    {:create (get f :create 0)
     :update (get f :update 0)
     :delete (get f :delete 0)
     :cmds (count (filter #(and (mutating? %) (seq (op-cmds %))) ops))}))

;; ---------------------------------------------------------------- generic file ops

(defn- dissoc-in
  "Remove one key deep inside a map, leaving its parents alone."
  [m path]
  (if (= 1 (count path))
    (dissoc m (first path))
    (if (get-in m (butlast path))
      (update-in m (butlast path) dissoc (last path))
      m)))

(defn unreadable-json
  "Why `file` is not JSON — it exists, holds something, and does not parse —
   or nil. `u/read-json` reads such a file as nil, and any edit on top of
   that would write a new file over it: a settings.json would keep only the
   keys agentctl declared, a hooks.json only agentctl's hooks."
  [file]
  (when-let [s (u/slurp-safe file)]
    (when-not (str/blank? s)
      (try (json/parse-string s) nil
           (catch Exception e (or (first (str/split-lines (str (ex-message e)))) "does not parse"))))))

(defn refused-json-op
  "The op an edit plans in a file that does not parse: shown in the plan,
   and failing when run, so apply! exits non-zero, the file stays byte for
   byte, and the state keeps what it last recorded."
  [{:keys [tool kind id file path project]} why]
  (let [msg (str (u/tilde file) " is not valid JSON (" why "): fix it by hand — agentctl will not rewrite it")]
    (op {:project project :action :update :tool tool :kind kind :id id :target file
         :path (mapv u/kw->str path) :summary msg :warn true :risk :medium
         :exec! (fn [] (throw (ex-info msg {:path file})))})))

(defn read-json-strict
  "The file's JSON, {} when absent or blank; throws when it does not parse."
  [file]
  (when-let [why (unreadable-json file)]
    (throw (ex-info (str (u/tilde file) " is not valid JSON (" why "): left untouched") {:path file})))
  (or (u/read-json file) {}))

(defn json-unset-op
  "Op that removes `path` from a JSON file. Nothing to remove is no op at all —
   an entry agentctl once wrote may already be gone by hand."
  [{:keys [tool kind id file path summary note risk project] :as args}]
  (if-let [why (unreadable-json file)]
    (refused-json-op args why)
  (let [before (get-in (u/read-json file) path)]
    (when (some? before)
      (op {:project project :action :delete :tool tool :kind kind :id id
           :target file
           :path (mapv u/kw->str path)
           :summary (or summary (str/join "." (map u/kw->str path)))
           :note note
           :diffs [{:key (last path) :before before :after nil}]
           :risk (or risk :medium)
           :exec! (fn []
                    (u/backup! file)
                    (u/write-json! file (dissoc-in (read-json-strict file) path)))})))))

(defn json-set-op
  "Op that sets `path` (vector of keys) to `value` inside a JSON file."
  [{:keys [tool kind id file path value summary note risk compare-as project
           report-converged?] :as args}]
  (if-let [why (unreadable-json file)]
    (refused-json-op args why)
  (let [current (u/read-json file)
        before (get-in current path)
        ;; :compare-as :set — for lists the tool is free to reorder, where
        ;; order carries no meaning and a plain diff would report drift forever
        cmp (if (= :set compare-as) (comp set u/norm) u/norm)]
    (if (= (cmp before) (cmp value))
      ;; converged. Worth a line only where a reader would otherwise wonder
      ;; whether the setting was looked at at all
      (when report-converged?
        (op {:project project :action :noop :tool tool :kind kind :id id :target file}))
      (op {:project project :action (if (nil? before) :create :update)
           :tool tool :kind kind :id id
           :target file
           :path (mapv u/kw->str path)
           :summary (or summary (str (str/join "." (map u/kw->str path))))
           :note note
           :diffs [{:key (last path) :before before :after value}]
           :risk (or risk :low)
           ;; re-read at execution time: several ops may target one file in a
           ;; single run, and a stale snapshot would drop the earlier writes
           :exec! (fn []
                    (u/backup! file)
                    (u/write-json! file (assoc-in (read-json-strict file) path value)))})))))

(defn json-remove-op
  [{:keys [tool kind id file path summary project] :as args}]
  (if-let [why (unreadable-json file)]
    (refused-json-op args why)
  (let [current (u/read-json file)
        before (get-in current path)]
    (when (some? before)
      (op {:project project :action :delete
           :tool tool :kind kind :id id
           :target file
           :path (mapv u/kw->str path)
           :summary (or summary (str "remove " (str/join "." (map u/kw->str path))))
           :diffs [{:key (last path) :before before :after nil}]
           :risk :medium
           :exec! (fn []
                    (u/backup! file)
                    (u/write-json! file (u/dissoc-in (read-json-strict file) (vec path))))})))))

(defn- sans-nils
  "`v` with every nil-valued map entry dropped, at any depth."
  [v]
  (cond
    (map? v) (into {} (keep (fn [[k x]] (when (some? x) [k (sans-nils x)]))) v)
    (sequential? v) (mapv sans-nils v)
    :else v))

(defn- same-element?
  "Do two array elements say the same thing? A vendor installer writes
   `\"matcher\": null` where agentctl leaves the key out; read back, those
   are one hook, not two."
  [a b]
  (= (u/norm (sans-nils a)) (u/norm (sans-nils b))))

(defn json-array-merge-op
  "Op that keeps exactly one caller-owned element inside a JSON array at
   `path`, leaving every other element untouched — the shape a hooks array
   needs when another tool (an editor extension, an orchestrator) also
   appends entries to it. An array carries no keys of its own, so membership
   is checked by value, not by index.

   `old` is the exact element agentctl wrote for this id last time (from the
   state manifest; nil if never written before, or already gone by hand).
   `value` is what it should be now. If `value` already sits in the array,
   nothing to do; otherwise `old` is removed (if still present) and `value`
   appended — or, with `:in-place?`, written at `old`'s index, for a reader
   that keys state by position (Codex's hook trust) and would otherwise see
   every later element move."
  [{:keys [tool kind id file path old value summary note risk project
           report-converged? in-place?] :as args}]
  (if-let [why (unreadable-json file)]
    (refused-json-op args why)
  (let [current (vec (or (get-in (u/read-json file) path) []))
        present? (fn [v] (some #(same-element? % v) current))]
    (if (present? value)
      (when report-converged?
        (op {:project project :action :noop :tool tool :kind kind :id id :target file}))
      (let [stale? (and (some? old) (present? old))]
        (op {:project project :action (if stale? :update :create)
             :tool tool :kind kind :id id
             :target file
             :path (mapv u/kw->str path)
             :summary summary
             :note note
             :diffs [{:key (last path) :before (when stale? old) :after value}]
             :risk (or risk :low)
             ;; re-read at execution time: several ops may target one file in
             ;; a single run, and a stale snapshot would drop earlier writes
             :exec! (fn []
                      (u/backup! file)
                      (let [data (read-json-strict file)
                            arr (vec (or (get-in data path) []))
                            same? (fn [x] #(same-element? % x))
                            at (when (and in-place? (some? old) (not-any? (same? value) arr))
                                 (first (keep-indexed (fn [i x] (when ((same? old) x) i)) arr)))
                            arr'' (if at
                                    (assoc arr at value)
                                    (let [arr' (vec (remove (same? old) arr))]
                                      (if (some (same? value) arr') arr' (conj arr' value))))]
                        (u/write-json! file (assoc-in data path arr''))))}))))))

(defn json-union-op
  "Op that makes the JSON array at `path` hold every element of `values`,
   keeping whatever else is there, in its order. The array is shared — the
   tool and the user add to it too — so nothing is removed and an element
   already present is not added twice. Nothing missing is no op at all."
  [{:keys [tool kind id file path values summary note risk project]}]
  (let [current (get-in (u/read-json file) path)
        have (set (map u/norm current))
        missing (vec (remove #(have (u/norm %)) values))]
    (when (seq missing)
      (op {:project project :action (if (nil? current) :create :update)
           :tool tool :kind kind :id id
           :target file
           :path (mapv u/kw->str path)
           :summary summary
           :note note
           :diffs [{:key (last path) :before current :after (into (vec current) missing)}]
           ;; what this op puts in the array, for a caller that has to take it
           ;; out again later — and only that: the rest was someone else's
           :added missing
           :risk (or risk :low)
           ;; re-read at execution time: the tool rewrites this array itself,
           ;; and several ops may target one file in a single run
           :exec! (fn []
                    (u/backup! file)
                    (let [data (read-json-strict file)
                          arr (vec (get-in data path))
                          have (set (map u/norm arr))]
                      (u/write-json! file (assoc-in data path
                                                    (into arr (remove #(have (u/norm %)) values))))))}))))

(defn json-array-unset-op
  "Op that removes one caller-owned element (`old`) from a JSON array at
   `path`. Nothing to remove is no op at all — mirrors `json-unset-op`."
  [{:keys [tool kind id file path old summary note risk project] :as args}]
  (if-let [why (unreadable-json file)]
    (refused-json-op args why)
  (let [current (vec (or (get-in (u/read-json file) path) []))]
    (when (some #(same-element? % old) current)
      (op {:project project :action :delete :tool tool :kind kind :id id
           :target file
           :path (mapv u/kw->str path)
           :summary (or summary "removed from agents.edn")
           :note note
           :diffs [{:key (last path) :before old :after nil}]
           :risk (or risk :medium)
           :exec! (fn []
                    (u/backup! file)
                    (let [data (read-json-strict file)
                          arr (vec (or (get-in data path) []))
                          arr' (vec (remove #(same-element? % old) arr))]
                      (u/write-json! file (assoc-in data path arr'))))})))))

(defn json-array-place-ops
  "The ops that keep one caller-owned element in the array at `path`, given
   where it was last written (`old-path`, from the state manifest). An
   element whose array changed — a hook whose `:event` changed — is removed
   from the old array and added to the new one in the same apply;
   `json-array-merge-op` alone looks for `old` only at `path`, so it would
   add the new element and leave the old one behind, owned by nobody."
  [{:keys [path old old-path] :as args}]
  (let [moved? (and (some? old) (seq old-path)
                    (not= (mapv u/kw->str old-path) (mapv u/kw->str path)))]
    (if moved?
      (keep identity [(json-array-unset-op (assoc args :path (vec old-path)
                                                  :summary (str "moved to " (str/join "." (map u/kw->str path)))))
                      (json-array-merge-op (dissoc args :old))])
      (keep identity [(json-array-merge-op args)]))))

(defn yaml-set-op
  [{:keys [tool kind id file path value summary risk project]}]
  (let [current (u/read-yaml file)
        before (get-in current path)]
    (when (not= (u/norm before) (u/norm value))
      (op {:project project :action (if (nil? before) :create :update)
           :tool tool :kind kind :id id
           :target file
           :path (mapv u/kw->str path)
           :summary (or summary (str (str/join "." (map u/kw->str path))))
           :diffs [{:key (last path) :before before :after value}]
           :risk (or risk :low)
           :exec! (fn []
                    (u/backup! file)
                    (u/write-yaml! file (assoc-in (or (u/read-yaml file) {}) path value)))}))))

(defn yaml-remove-op
  [{:keys [tool kind id file path summary project]}]
  (let [current (u/read-yaml file)
        before (get-in current path)]
    (when (some? before)
      (op {:project project :action :delete :tool tool :kind kind :id id :target file
           :path (mapv u/kw->str path)
           :summary (or summary (str "remove " (str/join "." (map u/kw->str path))))
           :diffs [{:key (last path) :before before :after nil}]
           :risk :medium
           :exec! (fn [] (u/backup! file)
                    (u/write-yaml! file (u/dissoc-in (or (u/read-yaml file) {}) (vec path))))}))))

(defn toml-set-op
  "Set scalar keys inside a TOML table via surgical edit."
  [{:keys [tool kind id file table kvs summary note risk project field-prefix]}]
  (let [current (toml/read-toml file)
        before (if (seq table) (get-in current (vec (map u/kw->str table))) current)
        diffs (field-diffs (into {} (map (fn [[k v]] [(u/kw->str k) v])) before)
                           (into {} (map (fn [[k v]] [(u/kw->str k) v])) kvs)
                           (map (comp u/kw->str key) kvs))]
    (when (seq diffs)
      (op {:project project :action (if (nil? before) :create :update)
           :tool tool :kind kind :id id :target file
           :path (mapv u/kw->str table)
           :summary summary
           :note note
           :diffs diffs
           :field-prefix field-prefix
           :risk (or risk :low)
           :exec! (fn []
                    (u/backup! file)
                    (toml/update-file! file #(toml/set-keys % (vec table) kvs)))}))))

(defn toml-tree-ops
  "`toml-set-op`s that make the nested map `value` hold under `table`: one op
   per table, each owning only the keys it declares, so hand-added keys in the
   same tables survive. Every nested map is a table of its own: one the file
   holds inline (`domains = {…}`) is written into where it stands, entry by
   entry, and one it lacks gets a `[header]`. `id-for` names each table's op
   from its path below `table`; `field-prefix` heads each field's label in
   the plan."
  [{:keys [tool kind file table value summary project id-for field-prefix]}]
  (let [walk (fn walk [path sub m]
               (let [kvs (into {} (keep (fn [[k v]] (when-not (map? v) [(u/key-str k) v]))) m)]
                 (cons (when (seq kvs)
                         (toml-set-op {:tool tool :kind kind :project project :id (id-for sub)
                                       :file file :table path :kvs kvs
                                       :field-prefix (when field-prefix (str/join "." (cons field-prefix sub)))
                                       :summary (str summary (when (seq sub) (str " " (str/join "." sub))))}))
                       (mapcat (fn [[k v]] (walk (conj path (u/key-str k)) (conj sub (u/key-str k)) v))
                               (filter (comp map? val) m)))))]
    (remove nil? (walk (vec table) [] value))))

(defn toml-remove-table-op
  [{:keys [tool kind id file table summary project]}]
  (let [current (toml/read-toml file)
        before (get-in current (vec (map u/kw->str table)))]
    (when (some? before)
      (op {:project project :action :delete :tool tool :kind kind :id id :target file
           :path (mapv u/kw->str table)
           :summary (or summary (str "remove [" (toml/header-for table) "]"))
           :diffs [{:key (last table) :before before :after nil}]
           :risk :medium
           :exec! (fn [] (u/backup! file) (toml/update-file! file #(toml/remove-table % (vec table))))}))))

(defn- link-op*
  [{:keys [tool kind id src dest mode project]}]
  (let [mode (or mode :symlink)
        exists (u/exists? dest)
        current-target (when (and exists (babashka.fs/sym-link? dest))
                         (u/real-path dest))
        want (u/abs-path src)
        ;; a resource that already lives at the destination is satisfied as-is;
        ;; never replace a directory with a link to itself
        in-place (and exists (= (u/real-path dest) (u/real-path want)))
        ok (or in-place
               (case mode
                 :symlink (= current-target (u/real-path want))
                 :copy (and exists (u/same-content? dest src))))]
    (when-not ok
      (op {:project project :action (if exists :update :create)
           :tool tool :kind kind :id id
           :target dest
           :category :fs
           :entry (if (babashka.fs/directory? want) "folder" "file")
           :fs-op (name mode)
           :from (u/tilde want)
           :summary (str (name mode) " " (u/tilde want) " -> " (u/tilde dest))
           ;; the command a person would type for this. `exec!` does it through
           ;; fs so it can back the old path up first, but the effect is this
           :cmds [(case mode
                    :symlink (if exists ["ln" "-sfn" want dest] ["ln" "-s" want dest])
                    :copy ["cp" "-R" want dest])]
           :diffs [{:key :target :before (or current-target (when exists "<unmanaged file>")) :after want}]
           :risk (if exists :medium :low)
           :exec! (fn []
                    (when exists (u/backup! dest) (babashka.fs/delete-tree dest))
                    (babashka.fs/create-dirs (babashka.fs/parent dest))
                    (if (= :symlink mode)
                      (babashka.fs/create-sym-link dest want)
                      (if (babashka.fs/directory? want)
                        (babashka.fs/copy-tree want dest)
                        (babashka.fs/copy want dest))))}))))

(defn link-op
  "Ensure `dest` is a symlink to `src` (or a copy when mode = :copy).

   Those two are the whole taxonomy: how an entry gets to its destination.
   `:hardlink` would be the third and nothing asks for it yet — the shape
   here has room for it, and `:fs-op` is already the word the plan renders.

   A skill whose source is not on disk is reported and its link left as it
   is: a link to nothing would dangle, and a dangling one never reads as
   converged. (Other kinds may link what an earlier op in the same plan
   fetches, an extension's checkout, so they are not held to this.)"
  [{:keys [tool kind id src dest mode project] :as m}]
  (if (and (= :skills kind) (not (u/resolves? src)))
    (op {:project project :action :noop :warn true :conflict true :tool tool :kind kind :id id
         :target dest :from (u/tilde (u/abs-path src))
         :summary (str "source missing: " (u/tilde (u/abs-path src)) " — link not changed")})
    (link-op* m)))

(defn unlink-op
  [{:keys [tool kind id dest project]}]
  (when (u/exists? dest)
    (op {:project project :action :delete :tool tool :kind kind :id id :target dest
         :category :fs
         :entry (if (babashka.fs/directory? dest) "folder" "file")
         :fs-op "remove"
         :summary (str "remove " (u/tilde dest))
         :risk :medium
         :exec! (fn [] (u/backup! dest) (babashka.fs/delete-tree dest))})))
