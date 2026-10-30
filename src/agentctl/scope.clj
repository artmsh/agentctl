(ns agentctl.scope
  "Entity placement and bounded repository discovery. Paths are HOME-relative."
  (:require [agentctl.util :as u]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def max-depth 32)
(def ignored #{"node_modules" ".venv" "venv" ".git" ".jj" ".hg" "skill-packs"})
(def kinds [:settings :permissions :mcps :skills :skill-packs :providers :memory :trust :hooks])
(def keyed-kinds
  "Sections that are maps keyed by a source selector rather than vectors of rows."
  #{:skills :skill-packs})
(defn- keyed? [v] (and (map? v) (seq v) (every? vector? (keys v))))
(defn dsl? [raw] (some #(let [v (get raw %)] (or (vector? v) (keyed? v))) kinds))

;; ---------------------------------------------------------------- keyed sections

(def acli-aliases
  "Short agent-CLI names accepted by `:acli`, mapped onto the internal tool key."
  {:cc :claude :claude-code :claude :agy :antigravity})

(defn acli [v]
  (cond (nil? v) nil
        (= :all v) :all
        :else (mapv #(get acli-aliases % %) (if (keyword? v) [v] v))))

(def consumers
  "CLIs that read `:skills` and `:skill-packs` out of agents.edn themselves. They
   are valid `:acli` values there, but agentctl has nothing to write for them, so
   `row` drops them and the tool model below it never sees one."
  #{:awl})

(defn- provisioned
  "A row's tool selection without its consumers. A selection of consumers alone
   comes out as `[]`, not nil, which would mean every tool."
  [ts]
  (let [xs (if (keyword? ts) [ts] ts)]
    (if (and (coll? xs) (some consumers xs)) (vec (remove consumers xs)) ts)))

(defn basename [s] (keyword (str (fs/file-name (str/replace (str s) #"/+$" "")))))
(defn local-uri? [uri] (or (str/starts-with? uri "file://") (not (re-find #"^[a-z+]+://|^[^/]+@[^:/]+:" uri))))

(defn source
  "A `[:gh \"owner/repo\"]` or `[:uri \"…\"]` key as a URI, or nil."
  [[head arg :as k]]
  (when (and (= 2 (count k)) (string? arg) (not (str/blank? arg)))
    (case head
      :gh (str "https://github.com/" (str/replace arg #"^/+|/+$|\.git$" ""))
      :uri arg
      nil)))

(defn- non-blank? [s] (and (string? s) (not (str/blank? s))))

(defn- bad-key [kind k]
  (throw (ex-info (str "invalid " kind " key " (pr-str k)
                       (if (= kind :skills)
                         " — expected [:gh \"owner/repo\"], [:uri \"…\"], [pack \"skill\"] or [pack [\"skill\" …]]"
                         " — expected [:gh \"owner/repo\"] or [:uri \"…\"]"))
                  {:kind kind :key k})))

(defn- row
  "Common row shape: `:alias` names the entity, `:acli` selects the CLIs."
  [id v]
  (when-not (or (nil? v) (map? v)) (throw (ex-info "keyed entry value must be a map" {:id id})))
  (let [v (or v {})]
    (cond-> (-> (dissoc v :acli) (assoc :id (if-let [a (:alias v)] (keyword (name a)) id)))
      (contains? v :tools) (update :tools provisioned)
      (contains? v :acli) (assoc :tools (provisioned (acli (:acli v)))))))

(defn- pack-rows [packs]
  (vec (for [[k v] packs
             :let [uri (or (source k) (bad-key :skill-packs k))]]
         (assoc (row (basename uri) v) :uri uri))))

(defn- skill-rows
  "A skill key names where the skill comes from:
     [pack \"name\"]            a skill inside a declared pack (by `:alias` or id)
     [pack [\"a\" \"b\"]]       several skills of one pack sharing one value
     [:uri \"file:///dir\"]      a skill directory on disk
     [:gh \"owner/repo\"]        a repository that is a skill; fetched as an
                                  implicit pack, reusing a declared pack of the same URI"
  [skills packs]
  (let [pack-ids (set (map :id packs))]
    (reduce
     (fn [{:keys [skills packs]} [k v]]
       (let [[head arg] k]
         (cond
           (and (symbol? head) (= 2 (count k))
                (or (non-blank? arg) (and (vector? arg) (seq arg) (every? non-blank? arg))))
           (let [pid (keyword (name head))
                 names (if (string? arg) [arg] arg)]
             (when-not (pack-ids pid)
               (throw (ex-info (str "skill " (pr-str k) " names undefined pack " head
                                    " — declare it under :skill-packs with {:alias " head "}")
                               {:key k})))
             (when (and (next names) (:alias v))
               (throw (ex-info (str "skill " (pr-str k) " gives one :alias to several skills") {:key k})))
             {:skills (into skills (map #(assoc (row (basename %) v) :from pid :subdir %)) names) :packs packs})

           (source k)
           (let [uri (source k) id (basename uri)]
             (if (local-uri? uri)
               {:skills (conj skills (assoc (row id v) :path (str/replace uri #"^file://" ""))) :packs packs}
               (let [existing (some #(when (= uri (:uri %)) %) packs)
                     pid (or (:id existing) id)]
                 {:skills (conj skills (assoc (row id v) :from pid))
                  :packs (if existing packs (conj packs {:id pid :uri uri :in :none :implicit true}))})))

           :else (bad-key :skills k))))
     {:skills [] :packs packs}
     skills)))

(defn lower-keyed
  "Rewrite the keyed `:skill-packs` / `:skills` maps into entity rows."
  [raw]
  (doseq [k keyed-kinds :let [v (get raw k)]
          :when (and (some? v) (not (map? v)))]
    (throw (ex-info (str k " must be a map keyed by source, e.g. {[:gh \"owner/repo\"] {:in :all}}") {:kind k})))
  (if-not (some #(seq (get raw %)) keyed-kinds)
    (reduce #(cond-> %1 (map? (get %1 %2)) (assoc %2 [])) raw keyed-kinds)
    (let [packs (pack-rows (:skill-packs raw))
          {:keys [skills packs]} (skill-rows (:skills raw) packs)]
      (assoc raw :skill-packs packs :skills skills))))
(defn path [p]
  (let [p (u/expand p)]
    (str (fs/normalize (if (fs/absolute? p) p (str u/home "/" p))))))
(defn under? [parent child]
  (or (= parent child) (str/starts-with? child (str (str/replace parent #"/+$" "") "/"))))
(defn repo? [p] (some #(fs/exists? (str p "/" %)) [".git" ".jj" ".hg"]))
(defn repositories [root]
  (letfn [(visit [p depth]
            (cond
              (not (fs/directory? p)) []
              (repo? p) [(str p)]
              (>= depth max-depth) []
              :else (mapcat #(visit % (inc depth))
                            (sort-by str
                             (filter #(and (fs/directory? %) (not (fs/sym-link? %))
                                           (not (ignored (str (fs/file-name %)))))
                                     (try (fs/list-dir p) (catch Exception _ [])))))))]
    (vec (visit root 0))))
(defn selector [s]
  (cond
    (#{:all :none} s) s
    :else
    (let [xs (if (string? s) [s] s)]
      (when-not (and (vector? xs) (seq xs)
                     (every? #(and (string? %) (not (str/blank? %))
                                   (not (re-find #"[*?\[\]]" %))) xs)
                     (some #(not (str/starts-with? % "!")) xs)
                     (not-any? #{"!"} xs))
        (throw (ex-info "invalid :in selector: expected :all, :none, or paths with at least one positive" {:in s})))
      (vec (sort (distinct (map #(if (str/starts-with? % "!")
                                                 (str "!" (path (subs % 1))) (path %)) xs)))))))
(defn targets [s]
  (if (keyword? s) []
      (let [denies (map #(subs % 1) (filter #(str/starts-with? % "!") s))]
        ;; a directory holding no repository is the place the user named
        (->> s (remove #(str/starts-with? % "!"))
             (mapcat #(or (not-empty (repositories %)) (when (fs/directory? %) [%])))
             distinct
             (remove #(some (fn [d] (under? d %)) denies)) sort vec))))
(defn matches? [s p]
  (and (vector? s)
       (some #(and (not (str/starts-with? % "!")) (under? % p)) s)
       (not-any? #(and (str/starts-with? % "!") (under? (subs % 1) p)) s)))
(defn rank [s target]
  (if (keyword? s) 0
      (apply max 0 (for [p s :when (and (not (str/starts-with? p "!")) (under? p target))]
                     (count (remove str/blank? (str/split p #"/")))))))
(defn digest [x]
  (subs (format "%064x" (java.math.BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                                       (.getBytes (pr-str x) "UTF-8")))) 0 16))
(defn target-id [p] (keyword (str "target-" (digest p))))
(def metadata-keys [:id :in :tools :for :at :scope :acli])
(defn entities [raw]
  (let [raw (lower-keyed raw)
        resolve-targets (memoize targets)
        es (vec (mapcat
                (fn [kind]
                  (let [rows (get raw kind [])]
                    (when-not (vector? rows)
                      (throw (ex-info "entity sections must be vectors" {:kind kind})))
                    (map-indexed
                     (fn [i row]
                       (when-not (map? row) (throw (ex-info "entity must be a map" {:kind kind})))
                       (let [row (cond-> row (contains? row :acli) (assoc :tools (acli (:acli row))))]
                       (let [s (selector (get row :in (if (= kind :skill-packs) :none :all)))
                             ref (or (:uri row) (:path row) (when (= kind :memory) (:from row)))
                             id (or (:id row) (when ref (keyword (str (fs/file-name (str/replace ref #"/+$" "")))))
                                    (keyword (digest s)))]
                         (when-not (keyword? id) (throw (ex-info ":id must be a keyword" {:id id})))
                         (when-not (contains? #{nil :machine :repo} (:at row))
                           (throw (ex-info ":at must be :machine or :repo" {:id id})))
                         (when-not (contains? #{nil :global :local :project :machine :repo} (:scope row))
                           (throw (ex-info "invalid deprecated :scope value" {:id id})))
                         {:kind kind :id id :in s :targets (resolve-targets s) :order i :decl row}))) rows))) kinds))]
    (doseq [[k rows] (group-by (juxt :kind :id) es)
            :when (next rows)
            :let [tools (map #(let [ts (or (get-in % [:decl :tools]) (get-in % [:decl :for]))]
                                (cond (keyword? ts) (when-not (= :all ts) #{ts})
                                      (seq ts) (set ts)))
                             rows)
                  bodies (distinct (remove empty? (map #(apply dissoc (:decl %) metadata-keys) rows)))]]
      ;; one MCP server may be placed differently per tool — user-wide for one,
      ;; inside a project for another — as long as no tool is asked twice
      (when-not (and (= :mcps (first k)) (every? some? tools) (apply distinct? (mapcat seq tools)))
        (throw (ex-info (str "duplicate " (pr-str k)
                             "; give distinct entries an explicit :alias (skills, skill-packs) or :id"
                             (when (= :mcps (first k))
                               ", or place one server per tool with disjoint :acli"))
                        {:duplicate k})))
      (when (next bodies)
        (throw (ex-info (str "duplicate " (pr-str k) "; rows sharing an :id must share one definition"
                             " — write it once and leave the other rows as placements")
                        {:duplicate k}))))
    es))

(defn compile-config
  "Lower entity vectors to the adapter target model. No writes occur here."
  [raw tools-for]
  (when (some #(seq (get raw %)) [:projects :executors :cli-code :extra-providers])
    (throw (ex-info "do not mix legacy project/executor sections with entity vectors; migrate them to :in stanzas" {})))
  (let [es (entities raw)
        selected (fn [e] (let [ts (or (get-in e [:decl :tools]) (get-in e [:decl :for]))]
                           (set (if (or (nil? ts) (= :all ts))
                                  (tools-for (case (:kind e) :trust :projects :skill-packs :skills (:kind e)))
                                  (if (keyword? ts) [ts] ts)))))
        ;; an :mcps id placed by several rows: one definition, a scope per tool
        shared (into {} (for [[id rows] (group-by :id (filter #(= :mcps (:kind %)) es))
                              :when (next rows)]
                          [id (first (remove empty? (map #(apply dissoc (:decl %) metadata-keys) rows)))]))
        paths (sort (distinct (mapcat :targets es)))
        base {:projects (into {} (map (fn [p] [(target-id p) {:path p :tools []}]) paths))}
        place (fn [cfg e target]
                (let [{:keys [kind id decl in]} e
                      pid (when target (target-id target))
                      ts (selected e)
                      body (apply dissoc decl metadata-keys)
                      body (if (= kind :settings)
                             (merge (zipmap (:on body) (repeat true))
                                    (zipmap (:off body) (repeat false))
                                    (dissoc body :on :off)) body)
                      at (or (:at decl) (if (#{:repo :project} (:scope decl)) :repo :machine))
                      scope (if (= in :all) :global (if (= at :repo) :project :local))
                      cfg (if pid (update-in cfg [:projects pid :tools] #(vec (set (concat % ts)))) cfg)]
                  (case kind
                    :settings (reduce #(update-in %1 (if pid [:projects pid :executors %2] [:executors %2]) merge body) cfg ts)
                    :permissions (if pid (if (ts :claude) (update-in cfg [:projects pid :permissions] merge body) cfg)
                                     (if (ts :claude) (update-in cfg [:executors :claude :permissions] merge body) cfg))
                    :hooks (if (ts :claude)
                             (update-in cfg (if pid [:projects pid :executors :claude :hooks] [:executors :claude :hooks]) merge
                                        (or (:hooks body) {id body})) cfg)
                    :trust (if pid (-> cfg (assoc-in [:projects pid :trusted] (get body :trusted true))
                                       (update-in [:projects pid :trust-by-tool] merge (zipmap ts (repeat (get body :trusted true))))) cfg)
                    :mcps (cond-> (if (contains? shared id)
                                    (update-in cfg [:mcps id]
                                               #(-> (merge % (get shared id))
                                                    (assoc :scope scope)
                                                    (update :tools (fnil into #{}) ts)
                                                    (update :scope-by-tool merge (zipmap ts (repeat scope)))))
                                    (assoc-in cfg [:mcps id] (assoc body :tools ts :scope scope)))
                            pid (update-in [:projects pid :mcp] (fnil conj []) id))
                    :skills (cond-> (assoc-in cfg [:skills id] (assoc body :tools ts :scope (if pid :project :global)))
                              pid (update-in [:projects pid :skills] (fnil conj []) id))
                    :skill-packs (cond-> (assoc-in cfg [:skill-packs id] body)
                                   pid (update-in [:projects pid :skills] (fnil conj []) id))
                    :providers (if pid cfg (assoc-in cfg [:providers id] (assoc body :tools ts)))
                    :memory (if pid cfg
                                (-> cfg
                                    (update :memory #(into {} (map (fn [[k m]] [k (update m :tools (fn [old] (set (remove ts old))))])) %))
                                    (assoc-in [:memory id] (assoc body :tools ts :scope :global))))
                    cfg)))
        globals (filter #(= :all (:in %)) es)
        ordered (fn [xs p] (sort-by (fn [e] [(rank (:in e) p) (if (get-in e [:decl :tools]) 1 0) (:order e)]) xs))
        cfg (reduce #(place %1 %2 nil) base (ordered globals nil))
        cfg (reduce (fn [cfg p] (reduce #(place %1 %2 p) cfg
                                        (ordered (filter #(some #{p} (:targets %)) es) p))) cfg paths)
        cfg (reduce (fn [c e] (if (= :skill-packs (:kind e))
                                (assoc-in c [:skill-packs (:id e)] (apply dissoc (:decl e) metadata-keys)) c)) cfg es)]
    ;; `:in :none` declares a source and places nothing, so it puts no tool to work
    {:raw cfg :entities es :active-tools (set (mapcat selected (remove #(= :none (:in %)) es)))}))
