(ns agentctl.adapters.common
  "Helpers every adapter shares."
  (:require [agentctl.config :as config]
            [agentctl.refs :as refs]
            [agentctl.util :as u]
            [clojure.string :as str]))

(defn for-tool
  "Resources from a normalized map that target `tool`."
  [m tool]
  (into {} (filter (fn [[_ v]] (contains? (:tools v) tool))) m))

(defn resolve-args
  "An MCP server with the references in its argv resolved (`refs/arg-refs`):
   `:args` as written, the references under `:arg-refs` by flag, and those
   that did not resolve under `:arg-issues`, which hold the server back as
   an unresolved env value does. An unresolved one stays as written."
  [m]
  (let [found (refs/arg-refs (:args m))]
    (if (or (::args-resolved m) (empty? found))
      m
      (let [res (for [[i flag r prefix] found] [i flag r prefix (refs/resolve-ref r)])]
        (assoc m
               ::args-resolved true
               :args (reduce (fn [args [i _ _ prefix {:keys [status value]}]]
                               (if (= :ok status) (assoc args i (str prefix value)) args))
                             (vec (:args m)) res)
               :arg-refs (into {} (map (fn [[_ flag r]] [(str "--" flag) r])) res)
               :arg-issues (for [[_ flag _ _ {:keys [status message]}] res
                                 :when (not= :ok status)]
                             {:key (str "--" flag) :status status :message message :where "args"}))))))

(defn for-this-tool
  "Apply a resource's :per-tool override for `tool`, if any.

   One endpoint often differs per agent — one tool may use a public host while
   others use an internal address — and a single flattened
   definition would silently rewrite one of them on every apply. An MCP
   server's argv references are resolved once the override is in."
  [res tool]
  (cond-> (if-let [override (get-in res [:per-tool tool])]
            (merge res (into {} (remove (comp nil? val)) override))
            res)
    (= :mcps (:kind res)) resolve-args))

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

(defn issues-in
  "`resolve-env`'s issues, each tagged with the map it came from — `env`,
   `headers` — so a report can say where the unresolved key sits."
  [where resolved]
  (map #(assoc % :where where) (:issues resolved)))

(declare declared-models)

(defn ref-issues
  "Every ref that did not resolve, tagged with where it sits: `places` maps
   a label (`env`, `headers`, `provider`) to an env-like map of refs."
  [places]
  (mapcat (fn [[where m]] (issues-in where (resolve-env m))) places))

(defn mcp-issues
  "A server's unresolved env, header and argv refs."
  [m]
  (concat (ref-issues [["env" (:env m)] ["headers" (:headers m)]]) (:arg-issues m)))

(defn declared-names
  "The env and header names a server declares, kept in the manifest beside
   it. A name agentctl declared and the declaration has since dropped is
   agentctl's to remove; a name it never declared was added by hand, and is
   kept."
  [m]
  (let [names (fn [x] (not-empty (vec (sort (map u/key-str (keys x))))))]
    (u/prune-nils {:env-names (names (:env m)) :header-names (names (:headers m))})))

(def name-tracking-tools
  "Tools whose server entries keep env vars and headers agentctl does not
   declare, so the manifest records the names it did."
  #{:claude :kimi :antigravity})

(defn kept-values
  "An entry's current env or header map (`k` is `:env-names` or
   `:header-names`) without the names the manifest entry `old` says agentctl
   declared and `m` no longer does, keyed by string: JSON reads a key back as
   a keyword, and merged with a declared one the name would be written twice."
  [cur old m k]
  (let [now (set (get (declared-names m) k))
        dropped (into #{} (remove now) (get old k))]
    (into {} (keep (fn [[kk v]] (let [kk (u/key-str kk)] (when-not (dropped kk) [kk v])))) cur)))

(defn mcp-secrets
  "Every ref a server declares where a credential goes, by name: env, headers
   and argv — what `holds-secret?` looks at."
  [m]
  (merge (:env m) (:headers m) (:arg-refs m)))

(defn provider-issues
  "A provider's unresolved refs: its key, unless `key?` is false (a key the
   tool reads from the environment itself), its headers, and each declared
   model's headers."
  [p key?]
  (ref-issues (concat [["provider" (when (and key? (:key p)) {"key" (:key p)})]
                       ["headers" (:headers p)]]
                      (for [m (declared-models p)]
                        [(str "models." (u/kw->str (:id m)) ".headers") (:headers m)]))))

(defn- unresolved-reason
  "`bitwarden vault locked (headers.api-key)`: the resolver's message and the
   key names it applies to. Names only — a value never reaches this line."
  [issues]
  (str/join "; " (for [[msg is] (group-by #(or (:message %) (name (:status %))) issues)]
                   (str msg " (" (str/join ", " (map #(str (:where %) "." (u/kw->str (:key %))) is)) ")"))))

(defn unresolved-op
  "Stands in for the op `base` would have been when its env or headers hold a
   secret that did not resolve: a locked vault, an unset variable, an emptied
   vault field. Writing the rest would leave a server that is configured and
   authenticates nothing — and since the missing value contributes nothing to
   the desired entry either, the next plan would see no drift. So nothing is
   written: the op has no `:exec!`, and `:unresolved` tells apply! the run
   has not converged."
  [base issues]
  (-> base
      (dissoc :exec! :diffs :cmds :note :path)
      (merge {:action :noop :warn true :unresolved true :risk :low
              :summary (str "⚠ secret unresolved: " (unresolved-reason issues) " — nothing written")})))

(def dialect-path
  "Where each dialect lives under a bare host. Only consulted for a provider
   that declares several: naming the host once is the whole point of writing
   them in one entry, and the path root is the part that differs -- an
   Anthropic-messages client appends `/v1/messages` itself, an OpenAI one
   expects the base to end at `/v1`."
  {"anthropic-messages" ""
   "anthropic" ""
   "openai-completions" "/v1"
   "responses" "/v1"})

(defn provider-dialect
  "The dialect this tool should speak to `p`, and the base URL for it, or nil
   when the provider serves nothing the tool can speak.

   A scalar :api is passed through untouched, `supported` and all: that is the
   pre-existing contract, and a provider naming exactly one dialect means it.
   A vector is a menu, resolved in declaration order."
  [p supported]
  (let [api (:api p)]
    (if (sequential? api)
      (when-let [d (first (filter supported api))]
        {:api d
         :url (str (str/replace (or (:url p) "") #"/+$" "") (get dialect-path d ""))})
      {:api api :url (:url p)})))

(defn no-dialect-op
  "A provider whose dialects this tool cannot speak is reported, not skipped:
   silently writing nothing looks identical to a provider that applied."
  [tool id p]
  {:action :noop :tool tool :kind :providers :id id :warn true
   :summary (str "no dialect " (name tool) " speaks in "
                 (str/join ", " (:api p)) " — provider skipped")})

(defn declared-models
  "The provider's models, as maps. `:all` has no list to give."
  [p]
  (when (vector? (:models p)) (:models p)))

(defn caps-not-written-ops
  "A provider tool with nowhere to put model capabilities says so, once per
   provider, instead of writing a model that quietly reads as text-only."
  [cfg tool]
  (when-not (config/model-caps-tools tool)
    (for [[id p] (for-tool-resources (:providers cfg) tool)
          :let [ms (filter :caps (declared-models p))]
          :when (seq ms)]
      {:action :noop :tool tool :kind :providers :id id :warn true
       :summary (str "model capabilities on " (str/join ", " (map :id ms))
                     " are not written for " (name tool) " — ignored")})))

(defn resolved-headers
  "Header map with every ref resolved. An unresolvable ref drops its header
   rather than writing a raw `$VAR` upstream as if it were the value."
  [headers]
  (not-empty (:values (resolve-env headers))))

(defn holds-secret?
  "True when the env/header map carries a credential — a secret ref that will be
   resolved at apply time, or a literal already written out in the DSL. A
   plaintext token is the more dangerous of the two: nothing resolves it, so the
   only warning it ever gets is this one."
  [env]
  (boolean (some (fn [[k v]] (or (refs/secret-ref? v) (refs/secret-shaped? k v))) env)))

(defn secret-keys
  "Names in an env map whose value is a credential: a secret ref, resolved at
   apply time, or a literal shaped like one. `raw` is the map as declared, for
   a literal whose ref no longer shows what it looked like."
  [refs raw]
  (into #{} (keep (fn [[k r]]
                    (when (or (refs/secret-ref? r) (refs/secret-shaped? k (get raw k)))
                      (u/kw->str k))))
        refs))

(defn mask-secrets
  "`[before after]` env maps as a plan may print them. A plan carries resolved
   values, and a name that says nothing about a credential (`X`, `DOCS_KEY`)
   is not masked by rendering, so the value under each name in `ks` — the
   secrets the declaration names now — is masked on both sides.

   The before side is masked as well wherever the value changes or goes
   away. What it held may have come from a ref the declaration no longer
   makes, and nothing on disk says which — dropping a secret, or replacing
   it with a literal, would otherwise print it. A value that changed but
   masks the same on both sides is marked changed, or a rotated secret
   would show no change at all."
  [before after ks]
  (let [ks (set ks)
        raw-b (u/norm before)
        raw-a (u/norm after)
        changed (into #{} (keep (fn [[k v]] (let [k (u/key-str k)]
                                              (when (not= v (get raw-a k)) k))))
                      raw-b)
        masked (fn [m ks] (when (map? m)
                            (into {} (map (fn [[k v]] (let [k (u/key-str k)]
                                                        [k (if (ks k) (refs/mask v) v)])))
                                  m)))
        b (masked before (into ks changed))
        a (masked after ks)]
    [b (when a
         (into {} (map (fn [[k v]] [k (if (and (ks k) (= v (get b k))
                                               (not= (get raw-b k) (get raw-a k)))
                                        (str v " (changed)")
                                        v)]))
               a))]))

(defn mask-all
  "`m` with every value masked: an env map that is being removed, whose
   declaration — the only thing that said which values were secrets — is
   already gone. Anything else is returned as it is."
  [m]
  (if (map? m)
    (into {} (map (fn [[k v]] [k (refs/mask v)])) m)
    m))

(def project-mcp-tools
  "Tools with a project-level MCP config. Everything else is user-wide only."
  #{:claude :codex})

(def project-skill-tools
  "Tools with a project-level skills directory. The rest read skills only from
   the user's own home. Codex, antigravity, kimi, pi and the DeepSeek Harness
   walk up from the working directory to the repository root looking for
   `.agents/skills`, so a project can carry its own — one directory all five
   of them read. Pi also reads its own `.pi/skills`, and reads either only in
   a project it trusts."
  #{:claude :codex :antigravity :kimi :dsh :pi})

(def project-scopes
  "Scopes that belong to one project: `:local` writes into the user's own
   runtime file under that project's entry, `:project` into the project's
   tracked `.mcp.json`."
  #{:local :project})

(defn mcp-scope
  "Where `tool` keeps server `m`. One server can be user-wide for one tool and
   inside a project for another; `:scope-by-tool` carries that, `:scope` is
   the answer when every tool agrees."
  [m tool]
  (get (:scope-by-tool m) tool (:scope m)))

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
                       [mid (resolve-args m)]))))
        (sort (:mcp proj))))

(defn project-scope-skip-ops
  "Report, rather than silently drop, project-scoped servers a tool cannot host.
   Only `project-mcp-tools` have a project-level MCP config."
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
