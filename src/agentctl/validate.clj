(ns agentctl.validate
  "Environment + config checks. `unknown` is a first-class outcome: a locked
   vault or an unreachable network must not be reported as a failure."
  (:require [agentctl.adapters.claude :as claude]
            [agentctl.adapters.codex :as codex]
            [agentctl.adapters.common :as common]
            [agentctl.adapters.omp :as omp]
            [agentctl.adapters.pi :as pi]
            [agentctl.config :as config]
            [agentctl.core :as core]
            [agentctl.plan :as plan]
            [agentctl.refs :as refs]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(defn- f [level where message & [data]]
  (u/prune-nils {:level level :where (vec where) :message message :data data}))

;; ---------------------------------------------------------------- checks

(defn check-clis [cfg]
  (let [needed (set (concat (keys (:tools cfg))
                            (mapcat :tools (vals (:mcps cfg)))
                            (mapcat :tools (vals (:providers cfg)))))]
    (for [t (sort-by name needed)]
      (if-let [path (u/which (name t))]
        (f :ok [:cli t] (u/tilde path))
        (f :error [:cli t] "CLI not found on PATH")))))

(defn check-packs [cfg deep?]
  (mapcat
   (fn [[id pack]]
     (case (:type pack)
       :file (if (u/exists? (:root pack))
               [(f :ok [:skill-packs id] (u/tilde (:root pack)))]
               [(f :error [:skill-packs id] (str "pack directory missing: " (u/tilde (:root pack))))])
       :git (concat
             (if (u/exists? (str (:root pack) "/.git"))
               [(f :ok [:skill-packs id] (str "cloned at " (u/tilde (:root pack))))]
               [(f :warn [:skill-packs id] "not cloned yet — `agentctl apply!` will fetch it")])
             (when deep?
               (let [{:keys [exit]} (u/sh "git" "ls-remote" "--exit-code" (:uri pack) "HEAD")]
                 [(if (zero? exit)
                    (f :ok [:skill-packs id] (str "remote reachable: " (:uri pack)))
                    (f :unknown [:skill-packs id] (str "remote unreachable: " (:uri pack))))])))
       [(f :error [:skill-packs id] "pack needs :uri")]))
   (:skill-packs cfg)))

(defn- skill-frontmatter-ok? [dir]
  (let [md (str dir "/SKILL.md")]
    (when-let [text (u/slurp-safe md)]
      (let [head (first (str/split text #"(?m)^---\s*$" 3))
            body (second (str/split text #"(?m)^---\s*$" 3))
            fm (or body head)]
        (and fm (re-find #"(?m)^name:\s*\S" fm) (re-find #"(?m)^description:\s*\S" fm))))))

(defn check-skills [cfg]
  (mapcat
   (fn [[id s]]
     (let [src (sources/skill-source cfg s)]
       (cond
         (nil? src) [(f :error [:skills id] "cannot resolve source (pack not fetched, or wrong :from)")]
         (not (u/exists? src)) [(f :error [:skills id] (str "source missing: " (u/tilde src)))]
         (not (u/exists? (str src "/SKILL.md"))) [(f :error [:skills id] (str "no SKILL.md in " (u/tilde src)))]
         (not (skill-frontmatter-ok? src)) [(f :warn [:skills id] "SKILL.md frontmatter lacks name/description")]
         :else [(f :ok [:skills id] (u/tilde src))])))
   (:skills cfg)))

(defn check-mcps [cfg deep?]
  (mapcat
   (fn [[id m]]
     (concat
      (cond
        (= :http (:transport m)) [(f :ok [:mcps id] (str "http " (:url m)))]
        (str/blank? (str (:command m))) []          ; already reported structurally
        :else (let [cmd (:command m)
                    relative? (not (str/starts-with? cmd "/"))
                    in-cwd (when (and relative? (:cwd m)) (str (:cwd m) "/" cmd))
                    resolved (or (u/which cmd)
                                 (when (u/exists? cmd) cmd)
                                 (when (u/exists? in-cwd) in-cwd))]
                [(cond
                   resolved (f :ok [:mcps id] (str "command " (u/tilde resolved)))
                   ;; a relative command is resolved by the agent, not by us
                   relative? (f :warn [:mcps id]
                                (str "relative command resolved by the tool: " cmd))
                   :else (f :error [:mcps id] (str "command not executable: " cmd)))]))
      (for [[k r] (:env m)
            :let [{:keys [status message]} (refs/resolve-ref r)]
            :when (not= :ok status)]
        (f (if (= :unknown status) :unknown :error) [:mcps id :env k]
           (str (refs/describe r) " — " (or message (name status)))))
      (when (and deep? (= :http (:transport m)))
        (let [{:keys [exit]} (u/sh "curl" "-sS" "-o" "/dev/null" "--max-time" "8" (:url m))]
          [(if (zero? exit)
             (f :ok [:mcps id] "endpoint reachable")
             (f :unknown [:mcps id] "endpoint unreachable"))]))))
   (:mcps cfg)))

(defn- provider-models [p]
  (let [key (refs/resolved-value (:key p))
        url (str (str/replace (:url p) #"/$" "") "/models")
        {:keys [exit out]} (u/sh "curl" "-sS" "--max-time" "10" url
                                 (when key ["-H" (str "Authorization: Bearer " key)]))]
    (when (zero? exit)
      (try (set (map :id (:data (json/parse-string out true)))) (catch Exception _ nil)))))

(defn check-providers [cfg deep?]
  (mapcat
   (fn [[id p]]
     (let [{:keys [status message]} (refs/resolve-ref (:key p))]
       (concat
        [(case status
           :ok (f :ok [:providers id] (str (:url p) " key=" (refs/describe (:key p))))
           :unknown (f :unknown [:providers id] (str (refs/describe (:key p)) " — " message))
           (f :error [:providers id] (str (refs/describe (:key p)) " — " message)))]
        (when (and (contains? (:tools p) :codex) (not= :env (:ref/kind (:key p))))
          [(f :warn [:providers id] "codex reads provider keys from env only — use :key $VAR for codex")])
        (when deep?
          (if-let [ids (provider-models p)]
            (concat [(f :ok [:providers id] (str (count ids) " models advertised"))]
                    (for [m (when (vector? (:models p)) (:models p))
                          :when (not (contains? ids m))]
                      (f :error [:providers id] (str "pinned model not offered: " m))))
            [(f :unknown [:providers id] "could not list /models")])))))
   (:providers cfg)))

(defn check-vault [cfg]
  (let [needs-bw (some #(= :bw (:ref/kind %))
                       (concat (map :key (vals (:providers cfg)))
                               (mapcat (comp vals :env) (vals (:mcps cfg)))))]
    (when needs-bw
      [(case @refs/bw-state
         :absent (f :error [:vault :bitwarden] "bw CLI not installed but !bw:// refs are used")
         :locked (f :unknown [:vault :bitwarden] "vault locked — run `bw unlock`; refs reported as unknown")
         (f :ok [:vault :bitwarden] "unlocked"))])))

;; ---------------------------------------------------------------- settings schema

(def ^:private settings-schema-cache-file
  (str u/home "/.config/agentctl/cache/claude-code-settings.schema.json"))

(defn- plausible-schema?
  "A 404/500 body that happens to parse as JSON must not get cached and
   reported as a clean match — require it to look like an actual schema."
  [schema]
  (and (map? schema) (seq (:properties schema))))

(defn- fetch-settings-schema!
  "Best-effort network fetch, cached to disk so a later offline run still has
   something to check against. `-L`: json.schemastore.org 301s to
   www.schemastore.org — a redirect body parsed as JSON is a silent no-op.
   `--fail`: turn a 4xx/5xx into a nonzero exit instead of an HTML/JSON error
   body we'd otherwise cache as if it were the schema."
  []
  (let [{:keys [exit out]} (u/sh "curl" "-sS" "-L" "--fail" "--max-time" "10" claude/settings-schema-url)]
    (when (zero? exit)
      (try
        (let [schema (json/parse-string out true)]
          (when (plausible-schema? schema)
            (u/write-json! settings-schema-cache-file schema)
            schema))
        (catch Exception _ nil)))))

(defn- settings-schema
  "Refetch under --deep; otherwise (or on fetch failure) fall back to whatever
   was cached by an earlier --deep run. A locked vault or dead network must not
   turn a lint check into an error — see the ns docstring."
  [deep?]
  (or (when deep? (fetch-settings-schema!))
      (let [cached (u/read-json settings-schema-cache-file)]
        (when (plausible-schema? cached) cached))))

(defn- trunc
  "Long regexes and enum lists read as noise on a terminal line — cut them the
   way `common/mcp-summary` cuts a long command line."
  [s]
  (if (> (count s) 80) (str (subs s 0 77) "...") s))

(defn- schema-value-type [v]
  (cond (nil? v) "null" (boolean? v) "boolean" (integer? v) "integer"
        (number? v) "number" (string? v) "string" (map? v) "object"
        (sequential? v) "array" :else "unknown"))

(defn- type-matches? [t v]
  (case t
    "string" (string? v) "boolean" (boolean? v) "integer" (integer? v)
    "number" (number? v) "object" (map? v) "array" (sequential? v)
    "null" (nil? v) true))

(defn- schema-deref
  "Resolve one level of `$ref` — the only form this schema uses is `#/$defs/x`."
  [root schema]
  (if-let [r (:$ref schema)]
    (or (get-in root (map keyword (rest (str/split r #"/")))) {})
    schema))

(defn- validate-value
  "Minimal JSON Schema subset: type, enum, const, pattern, minLength, required,
   properties/additionalProperties, items, $ref, anyOf/oneOf. Every other
   keyword is ignored, not failed — a schema update must never start emitting
   false positives here. Returns a seq of {:path :message}."
  [root schema value path]
  (let [schema (schema-deref root schema)]
    (cond
      (empty? schema) []

      (seq (:anyOf schema))
      (if (some #(empty? (validate-value root % value path)) (:anyOf schema))
        [] [{:path path :message "does not match any allowed shape (anyOf)"}])

      (seq (:oneOf schema))
      (if (some #(empty? (validate-value root % value path)) (:oneOf schema))
        [] [{:path path :message "does not match any allowed shape (oneOf)"}])

      :else
      (let [types (let [t (:type schema)]
                    (cond (string? t) [t] (sequential? t) (seq t) :else nil))]
        (if (and types (not (some #(type-matches? % value) types)))
          [{:path path :message (str "expected " (str/join "|" types) ", got " (schema-value-type value))}]
          (concat
           (when-let [en (seq (:enum schema))]
             (when-not (some #(= % value) en)
               [{:path path :message (str "value not in enum " (trunc (pr-str en)))}]))
           (when (and (contains? schema :const) (not= (:const schema) value))
             [{:path path :message (str "expected constant " (pr-str (:const schema)))}])
           (when (and (:pattern schema) (string? value)
                      (not (re-find (re-pattern (:pattern schema)) value)))
             [{:path path :message (str "does not match pattern " (trunc (:pattern schema)))}])
           (when (and (:minLength schema) (string? value) (< (count value) (:minLength schema)))
             [{:path path :message (str "shorter than minLength " (:minLength schema))}])
           (when (map? value)
             (concat
              (mapcat (fn [rq]
                        (when-not (contains? value (keyword rq))
                          [{:path (conj path rq) :message "required property missing"}]))
                      (:required schema))
              (mapcat (fn [[k v]]
                        (let [kn (u/kw->str k)
                              psub (get-in schema [:properties (keyword kn)])]
                          (cond
                            psub (validate-value root psub v (conj path kn))
                            (false? (:additionalProperties schema))
                            [{:path (conj path kn) :message "unknown property (schema forbids extras)"}]
                            :else [])))
                      value)))
           (when (and (sequential? value) (:items schema))
             (apply concat
                    (map-indexed (fn [i v] (validate-value root (:items schema) v (conj path (str "[" i "]"))))
                                 value)))))))))

(defn- schema-path->str [path]
  (let [s (reduce (fn [acc seg]
                     (cond (empty? acc) seg
                           (str/starts-with? seg "[") (str acc seg)
                           :else (str acc "." seg)))
                   "" path)]
    (if (str/blank? s) "(root)" s)))

(defn- check-settings-schema-file [schema managed-keys label file]
  (when (u/exists? file)
    (let [data (u/read-json file)]
      (cond
        (nil? data)
        [(f :warn [:settings label] (str "could not parse " (u/tilde file) " as JSON — schema check skipped"))]

        (not (map? data))
        [(f :warn [:settings label] "settings.json is not a JSON object — schema check skipped")]

        :else
        (let [errs (map (fn [{:keys [path message]}]
                           {:path (schema-path->str path) :message message
                            :level (if (contains? managed-keys (first path)) :error :warn)})
                         (validate-value schema schema data []))]
          (if (empty? errs)
            [(f :ok [:settings label] "matches claude-code-settings schema")]
            ;; one finding per distinct (message, level) — a rule violated by a
            ;; dozen fields is a dozen paths worth reading, not a dozen lines
            (for [[[message level] es] (group-by (juxt :message :level) errs)]
              (f level [:settings label]
                 (str (count es) " field(s): " message)
                 {:paths (vec (take 8 (map :path es)))}))))))))

(defn- project-schema-managed-keys
  "Top-level settings.json keys agentctl itself declares for this project —
   a schema violation there is agentctl's own bug and blocks; anything else in
   a hand-curated file is only worth a warning."
  [proj]
  (into (if (seq (:permissions proj)) #{"permissions"} #{})
        (map u/kw->str)
        (keys (first (common/map-settings (get-in proj [:tools claude/tool]) claude/setting-keys)))))

(defn check-claude-settings [cfg opts]
  (let [claude-projects (filter (fn [[_ proj]] (contains? (:for-tools proj) claude/tool)) (:projects cfg))]
    (when (or (u/exists? claude/settings-file) (seq claude-projects))
      (if-let [schema (settings-schema (:deep opts))]
        (let [global-managed (into #{} (map u/kw->str)
                                    (keys (first (common/map-settings (common/settings-for cfg claude/tool)
                                                                       claude/setting-keys))))]
          (concat
           (check-settings-schema-file schema global-managed "claude" claude/settings-file)
           (mapcat (fn [[id proj]]
                     (check-settings-schema-file schema (project-schema-managed-keys proj)
                                                 (str "claude/" (u/kw->str id))
                                                 (claude/project-settings-file proj)))
                   claude-projects)))
        [(f :unknown [:settings :claude] "schema unavailable — run `agentctl validate --deep` to fetch it")]))))

;; ---------------------------------------------------------------- hygiene

(def scan-targets
  [[:codex (str u/home "/.codex/config.toml")]
   [:pi (str u/home "/.pi/agent/models.json")]
   [:pi (str u/home "/.pi/agent/mcp.json")]
   [:omp (str u/home "/.omp/agent/models.yml")]
   [:claude (str u/home "/.claude.json")]])

(defn check-config-secrets
  "agents.edn is the one file agentctl owns outright, so a secret typed straight
   into it is worth naming: the ref forms exist precisely to keep it out."
  [cfg]
  (let [hits (volatile! [])]
    (walk/postwalk
     (fn [x]
       (when (map? x)
         (doseq [[k v] x
                 :when (and (string? v) (>= (count v) 12)
                            (re-find refs/secret-key-pattern (u/kw->str k))
                            (= :literal (:ref/kind (refs/parse v))))]
           (vswap! hits conj (u/kw->str k))))
       x)
     (:raw cfg))
    (when-let [ks (not-empty (distinct @hits))]
      [(f :warn [:hygiene :agents-edn]
          (str (count ks) " plaintext secret(s) in " (u/tilde (:source cfg))
               " — move to !bw://<folder>/<item>/<field> or $ENV_VAR")
          {:keys (vec (take 8 ks))})])))

(defn check-plaintext-secrets [cfg]
  (mapcat
   (fn [[tool path]]
     (when (u/exists? path)
       (let [text (u/slurp-safe path)
             hits (->> (str/split-lines (or text ""))
                       (keep (fn [line]
                               (when (and (re-find refs/secret-key-pattern line)
                                          (some #(re-find % (or (second (re-find #"[:=]\s*\"?([^\"\s,]{12,})\"?" line)) ""))
                                                refs/secret-value-patterns))
                                 (-> (first (str/split line #"[:=]"))
                                     str/trim
                                     (str/replace #"^\"|\"$" "")))))
                       (distinct))]
         (when (seq hits)
           [(f :warn [:hygiene tool] (str (count hits) " plaintext secret(s) in " (u/tilde path))
               {:keys (vec (take 8 hits))})]))))
   (concat scan-targets
           ;; .mcp.json is checked into the project — a resolved secret there
           ;; is a secret in git
           (for [[pid proj] (:projects cfg)] [pid (str (:path proj) "/.mcp.json")]))))

;; ---------------------------------------------------------------- run

(defn run [cfg opts]
  (let [deep? (:deep opts)
        st (state/load-state)
        drift (try (filter plan/mutating? (core/build-plan cfg st opts))
                   (catch Exception e [{:error (ex-message e)}]))
        findings (concat (map #(assoc % :level (:level %)) (config/structural-findings cfg))
                         (check-clis cfg)
                         (check-packs cfg deep?)
                         (check-skills cfg)
                         (check-mcps cfg deep?)
                         (check-providers cfg deep?)
                         (check-vault cfg)
                         (check-claude-settings cfg opts)
                         (check-plaintext-secrets cfg)
                         (check-config-secrets cfg)
                         [(if (seq drift)
                            (f :warn [:drift] (str (count drift) " pending change(s) — run `agentctl apply`"))
                            (f :ok [:drift] "environment matches agents.edn"))])
        by-level (group-by :level findings)]
    {:findings (vec findings)
     :errors (count (:error by-level))
     :warnings (count (:warn by-level))
     :unknown (count (:unknown by-level))
     :ok (count (:ok by-level))}))

(defn- mark [level]
  (case level :ok "✓" :warn "!" :error "✗" :unknown "?" "·"))

(def section-order
  [:cli :vault :skill-packs :skills :mcps :providers :settings :projects :memory :hygiene :drift])

(defn print-report [{:keys [findings errors warnings unknown ok]}]
  (doseq [[section fs] (sort-by (fn [[s _]]
                                  (let [i (.indexOf section-order s)]
                                    [(if (neg? i) 99 i) (u/kw->str s)]))
                                (group-by (comp first :where) findings))]
    (println (str "\n" (str/upper-case (u/kw->str section))))
    (doseq [{:keys [level where message data]} fs]
      (println (format "  %s %-22s %s%s"
                       (mark level)
                       (str/join "." (map u/kw->str (rest where)))
                       message
                       (if data (str " " (pr-str data)) "")))))
  (println (format "\n%d ok · %d warning(s) · %d unknown · %d error(s)" ok warnings unknown errors)))
