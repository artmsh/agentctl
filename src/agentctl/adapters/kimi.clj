(ns agentctl.adapters.kimi
  "Kimi Code CLI adapter.

   Kimi's data directory is `$KIMI_CODE_HOME`, falling back to `~/.kimi-code`.
   Three files matter, and each holds one kind of thing:

   - `config.toml` — the agent's own settings, plus `[providers.*]` and
     `[models.*]`. A model is a *separate* table from its provider: the
     provider holds the endpoint and the credential, the model holds the
     routing (`provider = \"…\"`) and the capabilities.
   - `mcp.json` — MCP servers, under an `mcpServers` wrapper. Kimi infers the
     transport from `command` vs `url`, so there is no `type` key to write.
   - `skills/` — the user's own skills directory.

   Kimi also reads `~/.agents/skills`, which is the directory the `skills` CLI
   fills and the one codex and antigravity read, so a project skill needs no
   Kimi-specific link.

   The file is written surgically rather than through the CLI: `kimi provider`
   only adds and removes whole providers from a registry, and it cannot
   express a model table or a custom header. The TOML surgery keeps the
   hooks another tool wrote (`# >>> orca-managed-kimi-hooks`) and the model
   entries agentctl does not own."
  (:require [agentctl.adapters.common :as common]
            [agentctl.plan :as plan]
            [agentctl.refs :as refs]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.toml :as toml]
            [agentctl.util :as u]
            [clojure.string :as str]))

(def tool :kimi)
(def cli "kimi")

(def home
  "Kimi's data directory. `KIMI_CODE_HOME` wins, exactly as Kimi itself
   resolves it — writing to `~/.kimi-code` when the user pointed Kimi
   elsewhere would leave the tool reading a config agentctl never wrote."
  (or (some-> (System/getenv "KIMI_CODE_HOME") not-empty)
      (str u/home "/.kimi-code")))

(def config-file (str home "/config.toml"))
(def mcp-file (str home "/mcp.json"))
(def skills-dir (str home "/skills"))
(def memory-file (str home "/AGENTS.md"))

(def setting-keys
  "Kimi's own spelling. `config.toml` mixes camelCase with the snake_case
   `default_model`, and both are read: the runtime converts snake_case keys to
   camelCase on the way in, and keeps the original under `raw`.

   `default_permission_mode` is Kimi's permission mode: `manual`, `auto` or
   `yolo`."
  {:model "default_model"
   :provider "default_provider"
   :permission-mode "default_permission_mode"
   :plan-mode "plan_mode"
   :yolo "yolo"
   :thinking "thinking"
   :telemetry "telemetry"
   :auto-session-title "auto_session_title"})

;; ---------------------------------------------------------------- settings

(defn settings-ops [cfg]
  (let [settings (common/settings-for cfg tool)
        [kvs unsupported] (common/map-settings settings setting-keys)]
    (concat
     (keep (fn [[k v]]
             (plan/toml-set-op {:tool tool :kind :settings :id k
                                :file config-file :table [] :kvs {k v}
                                :summary (str "config.toml " (u/kw->str k))}))
           kvs)
     (for [k unsupported]
       (plan/op {:action :noop :tool tool :kind :settings :id k
                 :summary (str "unsupported setting " k " for kimi — ignored")
                 :warn true})))))

;; ---------------------------------------------------------------- mcps

(defn- mcp-transport
  "Kimi's `transport` for a server: `stdio`, `http` or `sse`.

   Kimi infers it from `command` against `url` when the key is absent, but
   writing it out is what makes a transport change land — a server that was
   `sse` and is declared `http` keeps its old key otherwise, and the entry
   then describes two transports at once."
  [m]
  (case (:transport m)
    :http "http"
    :sse "sse"
    "stdio"))

(defn- mcp-entry
  "Kimi's spelling of a server: `transport`, `bearerTokenEnvVar` for the token's
   env name and `cwd` for the working directory. Managed fields are merged
   onto the current entry, so keys the DSL does not model survive.

   A transport change replaces the entry's fields rather than merging onto
   them: a server that was stdio and is now http would otherwise keep its
   `command` and `args` beside the `url`, and the two transports would both
   be on record at once."
  [m cur]
  (let [env (common/resolve-env (:env m))
        headers (common/resolve-env (:headers m))
        transport (mcp-transport m)
        cur (if (= transport "stdio")
              (dissoc cur :url :bearerTokenEnvVar)
              (dissoc cur :command :args))]
    (merge cur
           (:extra m)
           (u/prune-nils
            {:transport transport
             :command (:command m)
             :args (not-empty (vec (:args m)))
             :url (:url m)
             :cwd (:cwd m)
             :env (not-empty (merge (:env cur) (:values env)))
             :headers (not-empty (merge (:headers cur) (:values headers)))
             :bearerTokenEnvVar (:bearer-token-env m)
             ;; kimi spells it `enabled`, so a disabled server is written as
             ;; `enabled: false` — and an enabled one carries no key at all
             :enabled (when (false? (:enabled m)) false)}))))

(defn mcp-ops [cfg st]
  (let [desired (common/for-tool-resources (common/global-mcps (:mcps cfg) tool) tool)
        current (or (:mcpServers (u/read-json mcp-file)) {})
        managed (into #{} (map keyword) (state/managed-ids st tool :mcps))]
    (concat
     (keep (fn [[id m]]
             (let [secret? (common/holds-secret? (merge (:env m) (:headers m)))]
               (plan/json-set-op {:tool tool :kind :mcps :id id
                                  :file mcp-file
                                  :path [:mcpServers (keyword (u/kw->str id))]
                                  :value (mcp-entry m (get current (keyword (u/kw->str id))))
                                  :risk (if secret? :secret :low)
                                  :summary (common/mcp-summary m)})))
           desired)
     (keep (fn [id]
             (when (and (nil? (namespace id))
                        (not (contains? desired id))
                        (get current (keyword (u/kw->str id))))
               (plan/json-remove-op {:tool tool :kind :mcps :id id :file mcp-file
                                     :path [:mcpServers (keyword (u/kw->str id))]})))
           managed))))

;; ---------------------------------------------------------------- providers

(def dialects
  "Kimi's provider `type` is the wire format, and it names them differently
   from the DSL: an Anthropic provider is `anthropic`, an OpenAI one is
   `openai`, and Kimi's own service is `kimi`."
  {"anthropic-messages" "anthropic"
   "anthropic" "anthropic"
   "openai-completions" "openai"
   "responses" "openai_responses"})

(def native-dialects
  "Dialects a provider may declare that are already Kimi's own spelling."
  #{"anthropic" "openai" "kimi" "google-genai" "openai_responses" "vertexai"})

(def import-dialects
  "Kimi's provider type back to the DSL's dialect name."
  {"anthropic" "anthropic-messages"
   "openai" "openai-completions"
   "openai_responses" "responses"
   "kimi" "anthropic-messages"
   "google-genai" "openai-completions"
   "vertexai" "openai-completions"})

(defn import-api
  "The DSL's dialect for a Kimi provider type. Kimi's own spelling is not one
   the DSL uses, so it is translated back — a type the DSL does not know
   comes back as nil, and the provider is skipped rather than imported with
   a dialect it cannot speak."
  [type]
  (get import-dialects type))

(defn import-caps
  "Kimi's model table back into the DSL's declared capabilities.

   Kimi names the capabilities as a list — `thinking`, `image_in` — and keeps
   the sizes as numbers on the model. The DSL says the same three things in
   its own words."
  [m]
  (let [caps (set (get m "capabilities"))]
    (u/prune-nils
     {:context-window (get m "maxContextSize")
      :max-tokens (get m "maxOutputSize")
      :reasoning (when (contains? caps "thinking") true)
      :input (cond-> ["text"]
               (or (contains? caps "image_in") (contains? caps "image")) (conj "image"))})))

(defn- provider-type
  "Kimi's `type` for this provider, or nil when it speaks nothing Kimi knows.

   A provider that names one dialect is that dialect; one that names several
   is a menu, and Kimi takes the first it can speak."
  [p]
  (let [api (:api p)]
    (cond
      (and (string? api) (native-dialects api)) api
      (sequential? api) (some #(or (get dialects %) (when (native-dialects %) %)) api)
      (get dialects api) (get dialects api)
      (native-dialects api) api
      :else nil)))

(defn- model-entry
  "A model's own table, in the file's own spelling.

   The table's key is the alias the user selects; `model` is the upstream id
   it points at. The two are often the same and sometimes not, so an entry
   that already exists keeps its upstream id — the DSL carries one id per
   model, and overwriting the upstream with the alias would change the
   route.

   Only the fields agentctl manages are returned: a field Kimi stores that
   the DSL leaves alone — an upgrade rule — stays on the table as it was,
   because the TOML edit rewrites only the keys it is given."
  [m cur]
  (u/prune-nils
   {"provider" (:provider m)
    "model" (or (get cur "model") (:model m))
    "displayName" (or (get cur "displayName") (:name m) (:id m))
    "maxContextSize" (:context-window (:caps m))
    "maxOutputSize" (:max-tokens (:caps m))
    "capabilities" (not-empty
                    (cond-> []
                      (:reasoning (:caps m)) (conj "thinking")
                      (some #{"image" "image_in"} (:input (:caps m))) (conj "image_in")))}))

(defn- provider-entry
  "A provider's own table — only the fields agentctl manages, so a table Kimi
   holds in a shape the DSL does not model keeps it.

   A credential that names an environment variable is written as `apiKeyEnv`,
   the way Kimi itself takes one: the secret stays out of the file, and Kimi
   reads the variable when it starts. The variable need not be set now, so an
   unset one is not a failure. Any other ref is resolved and written out, and
   an unresolvable one leaves the key the provider already had alone — a
   locked vault must not wipe a working credential."
  [p cur]
  (let [key-ref (:key p)
        env-var (when (= :env (:ref/kind key-ref)) (:ref/var key-ref))
        {:keys [status value]} (refs/resolve-ref key-ref)]
    (u/prune-nils
     {"type" (:type p)
      "baseUrl" (:url p)
      "apiKeyEnv" env-var
      "apiKey" (when-not env-var
                 (if (= :ok status) value (get cur "apiKey")))
      "customHeaders" (some->> (merge (get cur "customHeaders")
                                       (common/resolved-headers (:headers p)))
                                (not-empty))})))

(defn- model-ops
  "One op per model. Kimi's schema requires `maxContextSize` on a model, so a
   model that declares no context window cannot be written at all — and
   writing one without it would leave Kimi unable to load its own config. That
   is reported instead."
  [pid models cur-models]
  (into []
        (keep (fn [m]
                (let [mcur (get cur-models (u/kw->str (:id m)))
                      id (keyword (str (name pid) "/" (u/kw->str (:id m))))]
                  (if (and (nil? (:context-window (:caps m)))
                           (nil? (get mcur "maxContextSize")))
                    (plan/op {:action :noop :tool tool :kind :providers :warn true
                              :id id
                              :summary (str "model " (u/kw->str (:id m))
                                            " has no context window — kimi requires maxContextSize, so it was not written")})
                    (plan/toml-set-op
                     {:tool tool :kind :providers :id id
                      :file config-file
                      :table ["models" (u/kw->str (:id m))]
                      :kvs (model-entry (assoc m :provider (u/kw->str pid)
                                               :model (u/kw->str (:id m))) mcur)
                      :summary (str "config.toml models." (u/kw->str (:id m)))})))))
        models))

(def ^:private unwritable-caps
  "The declared capabilities Kimi has no field for. Kimi records a model's
   context and output sizes and a list of capability names; the rest of the
   DSL's vocabulary — what a token costs, which thinking levels a route
   accepts — has nowhere to go."
  [:cost :thinking-level-map :compat])

(defn- caps-not-written-ops [cfg]
  (for [[id p] (common/for-tool-resources (:providers cfg) tool)
        m (common/declared-models p)
        k unwritable-caps
        :when (contains? (:caps m) k)]
    (plan/op {:action :noop :tool tool :kind :providers :warn true
              :id (keyword (str (name id) "/" (u/kw->str (:id m))))
              :summary (str "model " (u/kw->str (:id m)) " declares " (name k)
                            ", which kimi has no field for — ignored")})))

(defn provider-ops [cfg]
  (let [desired (common/for-tool-resources (:providers cfg) tool)
        current (try (toml/read-toml config-file) (catch Exception _ nil))]
    (mapcat
     (fn [[id p]]
       (let [type* (provider-type p)]
         (if-not type*
           [(plan/op (common/no-dialect-op tool id p))]
           (let [cur (get current (str "providers" (u/kw->str id)))
                 key-ref (:key p)
                 env-var (when (= :env (:ref/kind key-ref)) (:ref/var key-ref))
                 {:keys [status message]} (refs/resolve-ref key-ref)
                 provider-op (plan/toml-set-op
                              {:tool tool :kind :providers :id id
                               :file config-file
                               :table ["providers" (u/kw->str id)]
                               :kvs (provider-entry (assoc p :type type*) cur)
                               ;; only a resolved secret written out is a risk:
                               ;; an env var is named, not copied
                               :risk (if (and (not env-var) (refs/secret-ref? key-ref)) :secret :low)
                               :summary (str "config.toml providers." (u/kw->str id))})]
             (remove nil?
                     (concat
                      ;; an env var that is not set right now is not a failure:
                      ;; kimi reads it when it starts
                      [(when (and (:key p) (not env-var) (not= :ok status))
                         (plan/op {:action :noop :tool tool :kind :providers :id id :warn true
                                   :summary (str "key " (refs/describe (:key p)) " unresolved ("
                                                 (or message (name status))
                                                 ") — existing credential left untouched")}))]
                      [provider-op]
                      (model-ops id (common/declared-models p) (get current "models"))))))))
     desired)))

;; ---------------------------------------------------------------- skills

(defn skill-ops [cfg st]
  (let [desired (sources/for-tool cfg
                                  (into {} (filter (fn [[_ s]] (= :global (:scope s))))
                                        (sources/all-skills cfg))
                                  tool)
        managed (into #{} (map keyword) (state/managed-ids st tool :skills))]
    (concat
     (keep (fn [[id s]]
             (when (:source s)
               (plan/link-op {:tool tool :kind :skills :id id :src (:source s)
                              :dest (str skills-dir "/" (name id)) :mode (:mode s)})))
           desired)
     (keep (fn [id]
             (when (and (nil? (namespace id)) (not (contains? desired id)))
               (plan/unlink-op {:tool tool :kind :skills :id id
                                :dest (str skills-dir "/" (name id))})))
           managed))))

;; ---------------------------------------------------------------- memory

(defn memory-ops [cfg]
  (keep (fn [[id m]]
          (when (= :global (:scope m))
            (plan/link-op {:tool tool :kind :memory :id id :src (:from m)
                           :dest memory-file :mode (:mode m)})))
        (common/for-tool (:memory cfg) tool)))

;; ---------------------------------------------------------------- projects

(defn project-skills-dir
  "Kimi reads `.agents/skills` in a project — the same directory codex and
   antigravity read, so one install serves all three."
  [proj] (str (:path proj) "/.agents/skills"))

(defn project-ops [cfg st]
  (mapcat (fn [[id proj]]
            (when (contains? (:for-tools proj) tool)
              (sources/global-project-unlink-ops cfg tool id (project-skills-dir proj) skills-dir)))
          (:projects cfg)))

;; ---------------------------------------------------------------- entry

(defn plan [cfg st]
  (concat (settings-ops cfg)
          (mcp-ops cfg st)
          (common/project-scope-skip-ops cfg tool)
          (provider-ops cfg)
          (caps-not-written-ops cfg)
          (skill-ops cfg st)
          (memory-ops cfg)
          (project-ops cfg st)))

(defn present? [] (some? (u/which cli)))
