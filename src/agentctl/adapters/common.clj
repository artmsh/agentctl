(ns agentctl.adapters.common
  "Helpers every adapter shares."
  (:require [agentctl.refs :as refs]
            [agentctl.util :as u]
            [clojure.string :as str]))

(defn for-tool
  "Resources from a normalized map that target `tool`."
  [m tool]
  (into {} (filter (fn [[_ v]] (contains? (:tools v) tool))) m))

(defn for-this-tool
  "Apply a resource's :per-tool override for `tool`, if any.

   One endpoint often differs per agent — one tool may use a public host while
   others use an internal address — and a single flattened
   definition would silently rewrite one of them on every apply."
  [res tool]
  (if-let [override (get-in res [:per-tool tool])]
    (merge res (into {} (remove (comp nil? val)) override))
    res))

(defn for-tool-resources
  "Resources targeting `tool`, each already merged with its per-tool override."
  [m tool]
  (into {} (map (fn [[k v]] [k (for-this-tool v tool)])) (for-tool m tool)))

(defn resolve-env
  "Resolve a map of {k ref} into literal strings, collecting unresolved refs."
  [env]
  (reduce (fn [acc [k r]]
            (let [{:keys [status value message]} (refs/resolve-ref r)]
              (if (= :ok status)
                (assoc-in acc [:values (u/kw->str k)] value)
                (update acc :issues conj {:key k :ref r :status status :message message}))))
          {:values {} :issues []}
          env))

(defn holds-secret?
  "True when the env/header map carries a credential — a secret ref that will be
   resolved at apply time, or a literal already written out in the DSL. A
   plaintext token is the more dangerous of the two: nothing resolves it, so the
   only warning it ever gets is this one."
  [env]
  (boolean (some (fn [[k v]] (or (refs/secret-ref? v) (refs/secret-shaped? k v))) env)))

(def project-mcp-tools
  "Tools with a project-level MCP config. Everything else is user-wide only."
  #{:claude})

(def project-skill-tools
  "Tools with a project-level skills directory. The rest read skills only from
   the user's own home."
  #{:claude})

(def project-scopes
  "Scopes that belong to one project: `:local` writes into the user's own
   runtime file under that project's entry, `:project` into the project's
   tracked `.mcp.json`."
  #{:local :project})

(defn global-mcps
  "MCPs that belong to the machine. Project-scoped servers are provisioned by
   the project that names them, not user-wide."
  [mcps tool]
  (into {} (filter (fn [[_ m]] (= :global (:scope m)))) (for-tool mcps tool)))

(defn project-mcps
  "The project's MCP servers, in declaration form, for tools that can host them."
  [cfg proj tool]
  (into {} (keep (fn [mid]
                   (let [m (get-in cfg [:mcps mid])]
                     (when (and m (project-scopes (:scope m)) (contains? (:tools m) tool))
                       [mid m]))))
        (sort (:mcp proj))))

(defn project-scope-skip-ops
  "Report, rather than silently drop, project-scoped servers a tool cannot host.
   Only claude has a project-level MCP config; the rest are user-wide only."
  [cfg tool]
  (for [[pid proj] (:projects cfg)
        ;; a project that does not target this tool has nothing to skip
        :when (contains? (:for-tools proj) tool)
        :let [ms (project-mcps cfg proj tool)]
        :when (seq ms)]
    {:action :noop :risk :low :warn true :tool tool :kind :mcps :project pid
     :id (keyword (str (name pid) "/mcps"))
     :summary (str (count ms) " project-scoped MCP server(s) for " (name pid)
                   " (" (str/join ", " (map name (keys ms))) ") — "
                   (name tool) " has no project-level MCP config, skipped")}))

(defn mcp-summary
  "What this server actually is, for the plan line. The op already carries the
   tool and the id, so repeating \"stdio server\" on every row says nothing —
   the command line (or the endpoint) is the part that differs."
  [m]
  (let [line (if (= :http (:transport m))
               (str (:url m) "  (http)")
               (str/join " " (map u/tilde (cons (:command m) (:args m)))))
        line (if (> (count line) 96) (str (subs line 0 93) "...") line)]
    (str line
         (when-let [c (:cwd m)] (str "  in " (u/tilde c)))
         (when-not (:enabled m) "  (disabled)"))))

(defn settings-for
  "Effective settings for a tool: globals overridden per project when given."
  ([cfg tool] (get-in cfg [:tools tool] {}))
  ([cfg tool project]
   (merge (get-in cfg [:tools tool] {})
          (get-in project [:tools tool] {}))))

(defn map-settings
  "Translate declared settings into tool-native keys using `key-map`.
   Returns [native-kvs unsupported-keys]."
  [settings key-map]
  [(into {} (keep (fn [[k v]] (when-let [nk (get key-map k)] [nk v]))) settings)
   (remove (set (keys key-map)) (keys settings))])
