(ns agentctl.form
  "The control panel's view of agents.edn.

   The GUI's left pane is a form, and a form needs to know what fields exist
   before the file declares them. That knowledge already lives in the adapters
   (which settings each CLI understands) and in `config` (which tools support
   which kind), so it is read from there rather than restated.

   The model is derived from the buffer *as written* — `edn/read-string`, not
   `parse-config`. A field showing `high` because `$effort` expanded to it
   would be written back as the literal, and the binding the rest of the file
   shares would be gone."
  (:require [agentctl.adapters.antigravity :as antigravity]
            [agentctl.adapters.claude :as claude]
            [agentctl.adapters.codex :as codex]
            [agentctl.adapters.omp :as omp]
            [agentctl.adapters.pi :as pi]
            [agentctl.config :as config]
            [agentctl.edit :as edit]
            [agentctl.sources :as sources]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- catalog

(def settings-catalog
  "The settings each tool understands, taken from the adapter that writes them
   so the form cannot drift from what a plan would actually do."
  {:claude (vec (sort (keys claude/setting-keys)))
   :codex (vec (sort (keys codex/setting-keys)))
   :pi (vec (sort (keys pi/setting-keys)))
   :omp (vec (sort (conj (keys omp/setting-paths) :model-roles)))
   :antigravity (vec (sort (keys antigravity/setting-keys)))
   ;; llm reads two settings directly rather than through a key map
   :llm [:aliases :model]})

(def ^:private setting-fields
  "What kind of value a setting takes, where the shape is the same for every
   tool that has the setting at all."
  {:auto-compact {:type :bool} :ultracode {:type :bool} :skip-auto {:type :bool}
   :non-workspace-access {:type :bool} :enable-workflows {:type :bool}
   :env {:type :edn} :status-line {:type :edn} :model-roles {:type :edn}
   :aliases {:type :edn} :model-settings {:type :edn}
   :thinking {:suggest ["low" "medium" "high"]}
   :effort {:suggest ["low" "medium" "high"]}
   :reasoning-effort {:suggest ["low" "medium" "high"]}})

(def ^:private effort-levels ["low" "medium" "high" "xhigh"])

(def ^:private tool-setting-fields
  "Narrowings a tool's own published schema supports, and nothing more.

   Claude Code ships a JSON schema (`claude/settings-schema-url`), so `theme`
   and `effortLevel` are real closed sets and are offered as such — a value the
   file already holds that is outside the set (a `custom:<slug>` theme) still
   renders as a text box rather than being silently dropped from the picker.

   `model` is deliberately *not* a picker: the schema types it as a plain
   string because a full model id is as valid as an alias, so the aliases are
   offered as suggestions. codex, pi, omp and antigravity publish no schema
   this can be read off, so their settings stay open text with suggestions."
  {:claude {:theme {:type :str-enum
                    :options ["auto" "dark" "light" "dark-daltonized"
                              "light-daltonized" "dark-ansi" "light-ansi"]}
            :effort {:type :str-enum :options effort-levels}
            :thinking {:type :str-enum :options effort-levels}
            :output-style {:suggest ["default" "Proactive" "Explanatory" "Learning"]}
            :model {:suggest ["opus" "sonnet" "haiku" "fable"]
                    :hint "an alias or a full model id — the schema does not restrict it"}}
   :codex {:model {:suggest ["gpt-5-codex" "gpt-5"]}}})

(defn- setting-field
  "One executor setting as a form field: the shared shape, narrowed by anything
   the tool's own schema says."
  [tool k]
  (let [shared (get setting-fields k)
        narrowed (get-in tool-setting-fields [tool k])]
    (merge {:key k :type :scalar}
           ;; a closed set replaces the loose suggestions rather than sitting
           ;; beside them
           (cond-> shared (:options narrowed) (dissoc :suggest))
           narrowed)))

(defn- bool-settings
  "The tool's boolean settings — the flags `:on`/`:off` can name. Derived from
   the one place their type is written down, so a new flag shows up in the
   feature switches without a second list to remember."
  [tool]
  (vec (for [k (get settings-catalog tool)
             :when (= :bool (:type (setting-field tool k)))]
         k)))

;; ---------------------------------------------------------------- values

(def ^:private var-ref #"^\$\{?[A-Za-z_][A-Za-z0-9_-]*\}?$")

(defn- scalar-out
  "How a scalar reaches the browser. A `$name` symbol is shown as itself: it is
   a reference, and overwriting one has to be a deliberate act."
  [v]
  (cond (string? v) v
        (symbol? v) (str v)
        (keyword? v) (str v)
        (or (number? v) (boolean? v)) (str v)
        :else nil))

(defn- scalar-in
  "…and how it comes back. A whole value that reads as `$name` is the reference
   spelling; anything else is a string, including a path with $ inside it."
  [s]
  (let [s (str s)]
    (if (re-matches var-ref s) (symbol s) s)))

(defn- kw-in [v]
  (when (and v (not= "" v))
    (keyword (str/replace (str v) #"^:" ""))))

(defn coerce
  "JSON value + declared field type -> the EDN to write."
  [type value]
  (case (keyword type)
    ;; an emptied field is a removed key: writing "" would declare a setting
    ;; whose value is the empty string
    :scalar (let [s (str value)] (when-not (str/blank? s) (scalar-in s)))
    :bool (boolean value)
    :enum (kw-in value)
    ;; the tools' own schemas type these as strings, and the file reads better
    ;; with the same spelling the tool's docs use
    :str-enum (let [s (str value)] (when-not (str/blank? s) s))
    ;; a set, because that is how :on/:off are written in the DSL
    :flag-set (not-empty (into (sorted-set) (keep kw-in) value))
    :kw-set (mapv kw-in value)
    :id-set (mapv kw-in value)
    :edn (let [s (str/trim (str value))]
           (when-not (str/blank? s)
             (try (edn/read-string s)
                  (catch Exception e
                    (throw (ex-info (str "not readable as EDN: " (ex-message e)) {}))))))
    (throw (ex-info (str "unknown field type " type) {}))))

(defn- fits?
  "Whether the declared value is one this widget can show. A value outside a
   closed set is not an error — the set is what the tool documents, and the
   file may legitimately hold something else — so it falls back to a text box
   rather than being dropped from a picker that cannot represent it."
  [{:keys [type options]} v]
  (case (keyword type)
    :scalar (some? (scalar-out v))
    :bool (boolean? v)
    :enum (keyword? v)
    :str-enum (and (string? v) (contains? (set options) v))
    (:kw-set :id-set) (and (coll? v) (not (map? v)) (every? keyword? v))
    :flag-set (and (coll? v) (not (map? v)) (every? #(or (keyword? %) (symbol? %)) v))
    :edn true
    false))

(defn- edn-str [v]
  (binding [*print-length* nil] (pr-str v)))

(defn- path-str [x]
  (cond (keyword? x) (str x) (symbol? x) (str x) :else (pr-str x)))

;; ---------------------------------------------------------------- schema

(defn- f [key type & {:as opts}]
  (merge {:key key :type type} opts))

(defn- mcp-fields []
  [(f :cmd :scalar :hint "one shell line — the server's command")
   (f :command :scalar) (f :args :edn)
   (f :url :scalar) (f :transport :enum :aliases [:type] :options [:stdio :http :sse])
   (f :env :edn :hint "{\"TOKEN\" \"!bw://folder/item/field\"}")
   (f :headers :edn) (f :bearer-token-env :scalar) (f :cwd :scalar)
   (f :enabled :bool) (f :scope :enum :options [:global :local :project])
   (f :tools :kw-set :of :mcps) (f :per-tool :edn) (f :extra :edn)])

(defn- pack-groups
  "One group of switches per declared pack: every skill the pack has on disk,
   on when the file installs it.

   The pack's checkout is only known after normalization (`:root` is derived,
   not written), so the raw declaration is run through `config/norm-pack`
   first. A pack agentctl has not fetched yet can name nothing, and says so —
   an empty group and a pack with no skills must not look the same."
  [raw]
  (let [expanded (try (:raw (config/expand-defs raw)) (catch Exception _ raw))
        skills (:skills raw)]
    (vec (for [[id decl] (sort-by (comp path-str key) (:skill-packs expanded))
               :let [pack (try (config/norm-pack id decl) (catch Exception _ nil))
                     dirs (try (sources/skill-dirs pack) (catch Exception _ nil))]]
           (u/prune-nils
            {:id (path-str id)
             :label (u/kw->str id)
             :note (when (empty? dirs)
                     (if (some-> pack sources/materialized?)
                       "no skill directories in this pack"
                       "not on disk yet — apply! fetches it"))
             :options (vec (for [d dirs
                                 :let [sid (keyword (fs/file-name d))
                                       decl (get skills sid)]]
                             (u/prune-nils
                              {:id (path-str sid)
                               :label (name sid)
                               :path [(path-str :skills) (path-str sid)]
                               :edn (edn-str {:from id})
                               :on (contains? skills sid)
                               ;; installed, but from somewhere else: switching
                               ;; it off here would remove a skill this pack
                               ;; does not own
                               :elsewhere (boolean (and decl (map? decl)
                                                        (:from decl)
                                                        (not= id (:from decl))))})))})))))

(defn- pack-skill-ids
  "Every skill id living inside a materialized declared pack — a project's
   `:skills` can name these directly, with no `:skills` entry of its own."
  [raw]
  (let [expanded (try (:raw (config/expand-defs raw)) (catch Exception _ raw))]
    (distinct
     (for [[id decl] (:skill-packs expanded)
           :let [pack (try (config/norm-pack id decl) (catch Exception _ nil))
                 dirs (try (sources/skill-dirs pack) (catch Exception _ nil))]
           d dirs]
       (keyword (fs/file-name d))))))

(defn- schema
  "Sections, in the order the file itself is usually written."
  [raw]
  (let [pack-ids (vec (sort (keys (:skill-packs raw))))
        mcp-ids (vec (sort (keys (:mcps raw))))
        skill-ids (vec (sort (distinct (concat (keys (:skills raw)) pack-ids (pack-skill-ids raw)))))
        project-ids (vec (sort (keys (:projects raw))))]
    [{:key :#def :title "Bindings" :entry "binding" :id-kind :symbol
      :doc "$name expands anywhere below. Root level only."
      :template "\"\"" :value-only true :layout :table
      :fields [(f :value :edn)]}

     {:key :executors :title "Executors" :entry "tool" :id-kind :fixed
      :doc "Global per-CLI settings. A project's :executors override these."
      :ids (mapv name config/all-tools) :template "{}"
      :fields-fn (fn [id]
                   (let [tool (keyword id)
                         flags (bool-settings tool)]
                     (concat
                      ;; :on/:off are the terse spelling of the boolean
                      ;; settings, and the switch has three positions because
                      ;; the DSL has three: on, off, and unstated — unstated
                      ;; leaves the tool's own default alone
                      (when (seq flags)
                        [(f :features :flags :options flags
                            :hint "the tool's own default applies to anything left unset")])
                      (for [k (get settings-catalog tool)]
                        (setting-field tool k)))))}

     {:key :mcps :title "MCP servers" :entry "server" :id-kind :free
      :doc "A server a project names is provisioned into that project; the rest are machine-wide."
      :template "{:cmd \"\"}"
      :fields (mcp-fields)}

     {:key :skill-packs :title "Skill packs" :entry "pack" :id-kind :free
      :doc "Where skills come from: a git repo, or a directory already on disk."
      :template "{:uri \"\" :dir \"skills\"}"
      :fields [(f :uri :scalar) (f :type :enum :options [:git :file])
               (f :ref :scalar) (f :dir :scalar)]}

     {:key :skills :title "Skills" :entry "skill" :id-kind :free
      :doc "Installed skills. :from names a pack; :path is a directory."
      :groups (pack-groups raw)
      :template "{:from :pack}"
      :fields [(f :from :enum :options pack-ids) (f :path :scalar) (f :subdir :scalar)
               (f :mode :enum :options [:symlink :copy])
               (f :scope :enum :options [:global :project])
               (f :tools :kw-set :of :skills) (f :per-tool :edn)]}

     {:key :memory :title "Memory" :entry "file" :id-kind :free
      :doc "One memory file, linked into every agent."
      :template "{:from \"~/notes/AGENTS.md\"}"
      :fields [(f :from :scalar) (f :mode :enum :options [:symlink :copy])
               (f :scope :enum :options [:global :project])
               (f :project :enum :options project-ids)
               (f :tools :kw-set :of :memory)]}

     {:key :extra-providers :title "Providers" :entry "provider" :id-kind :free
      :doc "Extra model providers and per-model overrides."
      :template "{:url \"\"}"
      :fields [(f :url :scalar) (f :key :scalar :hint "$ENV_VAR or !bw://folder/item/field")
               (f :key-name :scalar)
               (f :api :edn :hint "one dialect, or a vector of them in preference order"
                  :suggest ["openai-completions" "responses" "anthropic-messages"])
               (f :headers :edn :hint "{\"x-header\" \"value or $ENV_VAR\"}")
               (f :models :edn :hint ":all, [\"vendor/model\"], or [{:id .. :headers {..}}]")
               (f :overrides :edn) (f :tools :kw-set :of :providers) (f :per-tool :edn)]}

     {:key :providers :title "Providers (:providers)" :entry "provider" :id-kind :free
      :only-if-present true :template "{:url \"\"}"
      :fields [(f :url :scalar) (f :key :scalar) (f :api :edn)
               (f :headers :edn)
               (f :models :edn) (f :overrides :edn) (f :tools :kw-set :of :providers)]}

     {:key :projects :title "Projects" :entry "project" :id-kind :free
      :doc "Per-project overrides. Naming executors is also how a project says who it is for."
      :template "{:executors #{:claude}}"
      :fields [(f :path :scalar) (f :parent :scalar) (f :trusted :bool)
               (f :executors :kw-set :of :projects
                  :hint "a set of tools, or a map of per-tool settings")
               (f :mcp :id-set :options mcp-ids)
               (f :skills :id-set :options skill-ids)
               (f :permissions :edn :hint "{:allow {:Bash [\"bb:*\"]}}")
               (f :agents :edn) (f :memory :edn)]}]))

;; ---------------------------------------------------------------- model

(defn- flag->data
  "`:on`/`:off` as one switch per boolean setting. Both keys travel with the
   field: moving a flag from on to off rewrites two nodes, not one."
  [decl base-path {:keys [key options hint]}]
  (let [state (fn [k] (cond (contains? (set (:on decl)) k) "on"
                            (contains? (set (:off decl)) k) "off"
                            :else ""))]
    (u/prune-nils
     {:key (name key)
      :type "flags"
      :path (conj base-path (path-str :on))
      :off-path (conj base-path (path-str :off))
      :present (boolean (or (contains? decl :on) (contains? decl :off)))
      :always true
      :options (vec (for [k options] {:key (name k) :state (state k)}))
      :hint hint})))

(defn- field->data [decl base-path {:keys [key type aliases options suggest hint of] :as field}]
  (let [k (or (some #(when (contains? decl %) %) (cons key aliases)) key)
        present? (contains? decl k)
        v (get decl k)
        type (if (and present? (not (fits? field v)))
               ;; a closed set the file steps outside of is still editable, as
               ;; the text it is
               (if (= :str-enum (keyword type)) :scalar :edn)
               type)
        suggest (if (and (= :scalar (keyword type)) (not-empty options) (empty? suggest))
                  (mapv str options)
                  suggest)
        options (cond of (mapv str (config/tools-for of))
                      ;; the tool's own spelling: these are strings in the file
                      (= :str-enum (keyword type)) (mapv str options)
                      ;; a value outside the set turned this into a text box;
                      ;; the set is a suggestion now, not a choice
                      (= :scalar (keyword type)) []
                      :else (mapv path-str options))]
    (u/prune-nils
     {:key (name key)
      :path (conj base-path (path-str k))
      :type (name type)
      :present present?
      :value (when present?
               (case type
                 :scalar (scalar-out v)
                 :bool (boolean v)
                 :str-enum (str v)
                 :enum (path-str v)
                 (:kw-set :id-set) (mapv path-str v)
                 :edn (edn-str v)))
      :options (not-empty options)
      :suggest (not-empty (vec suggest))
      :hint hint})))

(defn- entry->data [section id decl]
  (let [base [(path-str (:key section)) (path-str id)]
        value-only (:value-only section)
        fields (if value-only
                 [{:key :value :type (if (fits? {:type :scalar} decl) :scalar :edn)}]
                 (or (some-> (:fields-fn section) (apply [(name id)]) vec) (:fields section)))
        expansion (when (and (not value-only) (not (map? decl)) (some? decl))
                    ;; a bare string is the whole declaration; the first field
                    ;; edit has to turn it into the map it is shorthand for
                    (if (and (string? decl) (re-find #"^https?://" decl))
                      {:url decl} {:cmd (str decl)}))
        declared (cond (map? decl) decl expansion expansion :else {})
        known (into #{:on :off}
                    (mapcat (fn [fl] (cons (:key fl) (:aliases fl))) fields))
        shorthand (some-> expansion edn-str)]
    (u/prune-nils
     {:id (path-str id)
      :label (u/kw->str id)
      :path base
      :shorthand shorthand
      :fields (if value-only
                ;; the binding is its own value: one field, at the entry's path
                [(let [t (if (fits? {:type :scalar} decl) :scalar :edn)]
                   {:key (u/kw->str id) :path base :type (name t) :present true
                    :value (if (= :scalar t) (scalar-out decl) (edn-str decl))})]
                (mapv #(if (= :flags (:type %))
                         (flag->data declared base %)
                         (field->data declared base %))
                      fields))
      :extra (vec (for [[k v] (sort-by (comp path-str key) declared)
                        :when (not (known k))]
                    {:key (path-str k) :path (conj base (path-str k))
                     :type "edn" :present true :value (edn-str v)}))})))

(defn- section->data [raw section]
  (let [declared (get raw (:key section))
        ids (if (= :fixed (:id-kind section))
              (map keyword (:ids section))
              (keys declared))
        ids (if (= :fixed (:id-kind section))
              (sort-by #(if (contains? declared %) 0 1) ids)
              (sort-by path-str ids))]
    (u/prune-nils
     {:key (path-str (:key section))
      :title (:title section)
      :doc (:doc section)
      :entry (:entry section)
      :layout (some-> (:layout section) name)
      :groups (not-empty (:groups section))
      :id-kind (name (:id-kind section))
      :template (:template section)
      :entries (vec (for [id ids :when (or (contains? declared id)
                                           (= :fixed (:id-kind section)))]
                      (assoc (entry->data section id (get declared id))
                             :declared (contains? declared id))))})))

(defn model
  "The whole left pane: what the buffer declares, and what it could declare.
   Unparseable text is a message, not an exception — that is the normal state
   of a file being typed into, and the controls simply disable themselves."
  [text]
  (try
    (let [raw (edit/parse text)]
      (if-not (map? raw)
        {:ok false :error "agents.edn must contain a map"}
        {:ok true
         :sections (vec (for [s (schema raw)
                              :when (or (not (:only-if-present s))
                                        (contains? raw (:key s)))]
                          (section->data raw s)))
         :unknown (vec (for [[k v] raw
                             :when (not (config/known-top-keys k))]
                         {:key (path-str k) :path [(path-str k)]
                          :type "edn" :present true :value (edn-str v)}))}))
    (catch Exception e
      {:ok false :error (str "the buffer does not parse yet — " (ex-message e))})))

;; ---------------------------------------------------------------- ops

(defn ops->edits
  "Turn the browser's field changes into `edit/apply-ops` ops. Path elements
   arrive EDN-encoded, so `:#def` keys stay symbols and section keys stay
   keywords without a second convention."
  [ops]
  (mapv (fn [{:keys [op path type value edn]}]
          (let [path (mapv #(edn/read-string (str %)) path)]
            (when (empty? path) (throw (ex-info "an edit needs a path" {})))
            (cond
              (= "unset" (name (or op "set"))) {:op :unset :path path}
              (= "rename" (name (or op "set")))
              ;; the new name arrives spelled the way the section writes ids —
              ;; ":x" for a keyword, "x" for a :#def symbol — and is read back
              ;; whole, so a name with a space in it is refused rather than
              ;; silently truncated to its first token
              (let [nk (str/trim (str value))]
                (when-not (re-matches #":?[A-Za-z0-9][A-Za-z0-9._/-]*" nk)
                  (throw (ex-info (if (str/blank? nk)
                                    "a name cannot be empty"
                                    (str (pr-str nk) " is not a usable name"))
                                  {})))
                {:op :rename :path path :value (edn/read-string nk)})
              edn {:op (keyword (name (or op "set"))) :path path
                   :value (edn/read-string (str edn))}
              :else (let [v (coerce (or type :scalar) value)]
                      (if (nil? v)
                        {:op :unset :path path}
                        {:op :set :path path :value v})))))
        ops))
