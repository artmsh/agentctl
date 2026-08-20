(ns agentctl.plan
  "Operation records, diffing and terraform-style rendering."
  (:require [agentctl.refs :as refs]
            [agentctl.toml :as toml]
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

(defn- colorize [action s]
  (let [code (case action :create "32" :update "33" :delete "31" :noop "90" "0")]
    (if (System/getenv "NO_COLOR") s (str "\033[" code "m" s "\033[0m"))))

(defn- render-val
  "The key comes along so a credential is masked: a plan is read out loud, put
   in a ticket and pasted into chat — it is not a place for a live token."
  ([v] (render-val nil v))
  ([k v]
   (cond
     (refs/ref? v) (refs/describe v)
     (nil? v) "∅"
     (refs/credential? k v) (pr-str (refs/mask v))
     (string? v) (pr-str v)
     (coll? v) (pr-str (u/norm v))
     :else (pr-str v))))

(defn field-diffs
  "Field-level diff between two maps, restricted to `ks` when given."
  ([before after] (field-diffs before after (distinct (concat (keys before) (keys after)))))
  ([before after ks]
   (for [k ks
         :let [b (get before k) a (get after k)]
         :when (not= (u/norm b) (u/norm a))]
     {:key k :before b :after a})))

(defn- diff-action [b a]
  (cond (nil? b) :create (nil? a) :delete :else :update))

(defn- plain-map? [v] (and (map? v) (not (refs/ref? v))))

(defn- render-diff
  "One line per changed field, nested. Printing both sides of a map whole makes
   the reader diff it by eye — which is the job this output exists to do."
  [indent k b a]
  (let [act (diff-action b a)]
    (if (and (or (plain-map? b) (nil? b))
             (or (plain-map? a) (nil? a))
             (or (plain-map? b) (plain-map? a)))
      (cons (colorize act (str indent (sigil act) " " (u/kw->str k)))
            (mapcat (fn [{:keys [key before after]}]
                      (render-diff (str indent "  ") key before after))
                    (field-diffs (u/norm (or b {})) (u/norm (or a {})))))
      [(colorize act
                 (str indent (sigil act) " " (u/kw->str k) ": "
                      (case act
                        :create (render-val k a)
                        :delete (render-val k b)
                        (str (render-val k b) " -> " (render-val k a)))))])))

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
  (when-let [p (:project o)] (str "projects/" (u/kw->str p))))

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
      (str (name (:kind o)) "/" label))))

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
        [true (field-diffs (u/norm (or before {})) (u/norm (or after {})))])
      [false ds])))

(defn- render-cmd
  "A shell command the plan will run, marked `!` — the one kind of line here
   that is not a file edit, and the one a reader may want to run by hand."
  [argv]
  (str "  !" (str/join " " (map (comp u/tilde str) (remove nil? (flatten argv))))))

(defn- op-cmds [o] (when-let [c (:cmds o)] (if (vector? (first c)) c [c])))

(defn render-op [o {:keys [verbose]}]
  (let [cmds (op-cmds o)
        line (colorize (:action o) (head-line (:action o) o [(id-str (:id o))]))
        detail (when (and (:summary o) (not cmds)) (str "    " (:summary o)))
        note (when (:note o) (str "    " (:note o)))
        diffs (when (or verbose (= :update (:action o)))
                (mapcat (fn [{:keys [key before after]}]
                          (render-diff "      " key before after))
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
  (if (or collapsed? (> (count ds) 1))
    #(str (name (:id o)) "." (u/kw->str %))
    #(u/kw->str %)))

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

(defn render-plan
  [ops {:keys [show-noop] :as opts}]
  ;; a warning noop is the report that a declared setting went nowhere — hiding
  ;; it would make the config look applied when it was not. A converged check is
  ;; the opposite report, and just as worth having: the setting is declared, it
  ;; was looked at, and the file already agrees
  (let [visible (if show-noop
                  ops
                  (filter #(or (mutating? %) (:warn %) (quiet-noop? %)) ops))
        by-tool (group-by :tool visible)]
    (str/join
     "\n"
     (concat
      (for [tool (sort-by name (keys by-tool))
            :let [tool-ops (sort-by (juxt (comp name :kind) (comp u/kw->str :id)) (get by-tool tool))]
            s (cons (str "\n" (str/upper-case (name tool)))
                    (map #(render-group % opts) (chunk-ops tool-ops)))]
        s)))))

(defn summary-line [ops]
  (let [f (frequencies (map :action ops))]
    (format "%d to add, %d to change, %d to remove, %d unchanged"
            (get f :create 0) (get f :update 0) (get f :delete 0) (get f :noop 0))))

;; ---------------------------------------------------------------- generic file ops

(defn json-set-op
  "Op that sets `path` (vector of keys) to `value` inside a JSON file."
  [{:keys [tool kind id file path value summary note risk compare-as project
           report-converged?]}]
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
           :summary (or summary (str (str/join "." (map u/kw->str path))))
           :note note
           :diffs [{:key (last path) :before before :after value}]
           :risk (or risk :low)
           ;; re-read at execution time: several ops may target one file in a
           ;; single run, and a stale snapshot would drop the earlier writes
           :exec! (fn []
                    (u/backup! file)
                    (u/write-json! file (assoc-in (or (u/read-json file) {}) path value)))}))))

(defn json-remove-op
  [{:keys [tool kind id file path summary project]}]
  (let [current (u/read-json file)
        before (get-in current path)]
    (when (some? before)
      (op {:project project :action :delete
           :tool tool :kind kind :id id
           :target file
           :summary (or summary (str "remove " (str/join "." (map u/kw->str path))))
           :diffs [{:key (last path) :before before :after nil}]
           :risk :medium
           :exec! (fn []
                    (u/backup! file)
                    (u/write-json! file (u/dissoc-in (or (u/read-json file) {}) (vec path))))}))))

(defn yaml-set-op
  [{:keys [tool kind id file path value summary risk project]}]
  (let [current (u/read-yaml file)
        before (get-in current path)]
    (when (not= (u/norm before) (u/norm value))
      (op {:project project :action (if (nil? before) :create :update)
           :tool tool :kind kind :id id
           :target file
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
           :summary (or summary (str "remove " (str/join "." (map u/kw->str path))))
           :diffs [{:key (last path) :before before :after nil}]
           :risk :medium
           :exec! (fn [] (u/backup! file)
                    (u/write-yaml! file (u/dissoc-in (or (u/read-yaml file) {}) (vec path))))}))))

(defn toml-set-op
  "Set scalar keys inside a TOML table via surgical edit."
  [{:keys [tool kind id file table kvs summary note risk project]}]
  (let [current (try (toml/read-toml file) (catch Exception _ nil))
        before (if (seq table) (get-in current (vec (map u/kw->str table))) current)
        diffs (field-diffs (into {} (map (fn [[k v]] [(u/kw->str k) v])) before)
                           (into {} (map (fn [[k v]] [(u/kw->str k) v])) kvs)
                           (map (comp u/kw->str key) kvs))]
    (when (seq diffs)
      (op {:project project :action (if (nil? before) :create :update)
           :tool tool :kind kind :id id :target file
           :summary summary
           :note note
           :diffs diffs
           :risk (or risk :low)
           :exec! (fn []
                    (u/backup! file)
                    (toml/update-file! file #(toml/set-keys % (vec table) kvs)))}))))

(defn toml-remove-table-op
  [{:keys [tool kind id file table summary project]}]
  (let [current (try (toml/read-toml file) (catch Exception _ nil))
        before (get-in current (vec (map u/kw->str table)))]
    (when (some? before)
      (op {:project project :action :delete :tool tool :kind kind :id id :target file
           :summary (or summary (str "remove [" (toml/header-for table) "]"))
           :diffs [{:key (last table) :before before :after nil}]
           :risk :medium
           :exec! (fn [] (u/backup! file) (toml/update-file! file #(toml/remove-table % (vec table))))}))))

(defn link-op
  "Ensure `dest` is a symlink to `src` (or a copy when mode = :copy)."
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
                 :copy (and exists (= (u/path-sha256 dest) (u/path-sha256 src)))))]
    (when-not ok
      (op {:project project :action (if exists :update :create)
           :tool tool :kind kind :id id
           :target dest
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

(defn unlink-op
  [{:keys [tool kind id dest project]}]
  (when (u/exists? dest)
    (op {:project project :action :delete :tool tool :kind kind :id id :target dest
         :summary (str "remove " (u/tilde dest))
         :risk :medium
         :exec! (fn [] (u/backup! dest) (babashka.fs/delete-tree dest))})))
