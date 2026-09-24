(ns agentctl.adapters.antigravity
  "Antigravity CLI adapter (`agy`).

   Two roots, and the split is not cosmetic: `~/.gemini/antigravity-cli` holds
   the CLI's own settings blob, while `~/.gemini/config` is the *customization
   root* the agent scans at startup — skills, rules and MCP servers live there,
   in exactly the layout a project's `.agents/` uses.

   `agy mcp add` is deliberately not used: it rejects `--env` on http servers,
   so an http server carrying a token is unexpressible through the CLI, and it
   cannot round-trip the keys agentctl does not model. The file is plain JSON
   and small, so it is edited the way pi's and omp's are."
  (:require [agentctl.adapters.common :as common]
            [agentctl.plan :as plan]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.util :as u]
            [clojure.string :as str]))

(def tool :antigravity)
(def cli "agy")

(def home (str u/home "/.gemini/antigravity-cli"))
(def config-root (str u/home "/.gemini/config"))
(def settings-file (str home "/settings.json"))
(def mcp-file (str config-root "/mcp_config.json"))
(def skills-dir (str config-root "/skills"))
;; rules are discovered as `rules/*.md` under a customization root; a single
;; AGENTS.md there is the global equivalent of the other tools' memory file
(def memory-file (str config-root "/rules/AGENTS.md"))

(def setting-keys
  {:model :model
   :mode :agentMode
   :status-line :statusLine
   :artifact-review :artifactReviewPolicy
   :non-workspace-access :allowNonWorkspaceAccess})

;; ---------------------------------------------------------------- settings

(defn settings-ops [cfg]
  (let [settings (common/settings-for cfg tool)
        [kvs unsupported] (common/map-settings settings setting-keys)]
    (concat
     (keep (fn [[k v]]
             (plan/json-set-op {:tool tool :kind :settings :id k
                                :file settings-file :path [k] :value v
                                :summary (str "settings.json " (name k))}))
           kvs)
     (for [k unsupported]
       (plan/op {:action :noop :tool tool :kind :settings :id k
                 :summary (str "unsupported setting " k " for antigravity — ignored")
                 :warn true})))))

;; ---------------------------------------------------------------- mcps

(defn- mcp-entry
  "Antigravity's spelling of a server: `serverUrl` rather than `url`, and
   `disabled` rather than `enabled` — copying pi's keys verbatim would leave a
   server switched on while the plan reported it converged. Managed fields are
   merged onto the current entry so keys the DSL does not model survive."
  [m cur]
  (let [env (common/resolve-env (:env m))
        headers (common/resolve-env (:headers m))]
    (merge cur
           (:extra m)
           (u/prune-nils
            {:command (:command m)
             :args (not-empty (vec (:args m)))
             :serverUrl (:url m)
             :env (not-empty (merge (:env cur) (:values env)))
             :headers (not-empty (merge (:headers cur) (:values headers)))})
           ;; agy writes this key explicitly even when the server is on, so
           ;; always emitting it costs no churn
           {:disabled (not (:enabled m))})))

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
               (plan/json-remove-op {:tool tool :kind :mcps :id id
                                     :file mcp-file
                                     :path [:mcpServers (keyword (u/kw->str id))]})))
           managed))))

;; ---------------------------------------------------------------- skills

(defn skill-ops
  "User-wide skills. A skill a project named lives in that project's `.agents`
   and is installed by `project-skill-ops`."
  [cfg st]
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

(defn project-skills-dir [proj] (str (:path proj) "/.agents/skills"))

(defn- trust-ops
  "`trustedWorkspaces` is a bare array, not a map keyed by path, so writing the
   declared set would delete the workspaces agy trusted on its own. One op for
   the whole list, unioning onto what is there: agentctl adds, never removes.
   `:trusted false` is therefore a no-op rather than a revocation — dropping a
   path here is the user's call, not a side effect of flipping a flag."
  [cfg]
  (if (:entity-dsl? cfg)
    (keep (fn [[id proj]]
            (when (and (some? (:trusted proj)) (contains? (:for-tools proj) tool))
              (let [args {:tool tool :kind :projects :id id :project id :file settings-file
                          :path [:trustedWorkspaces] :old (:path proj) :value (:path proj)}
                    o (if (:trusted proj) (plan/json-array-merge-op args) (plan/json-array-unset-op args))]
                (when o (assoc o :array-element true))))) (:projects cfg))
  (let [current (vec (or (get (u/read-json settings-file) :trustedWorkspaces) []))
        wanted (for [[_ proj] (:projects cfg)
                     :when (and (:trusted proj) (contains? (:for-tools proj) tool))]
                 (:path proj))
        missing (remove (set current) (distinct wanted))]
    (when (seq missing)
      (keep identity
            [(plan/json-set-op {:tool tool :kind :projects :id :trusted-workspaces
                                :file settings-file :path [:trustedWorkspaces]
                                :value (into current missing)
                                :compare-as :set
                                :risk :medium
                                :summary (str "trust " (count missing) " workspace(s): "
                                              (str/join ", " (map u/tilde missing)))})])))))

(defn- project-skill-ops
  "The `skills` CLI installs into `.agents/skills` (`skills-cli`); this only
   drops project copies of user-wide skills."
  [cfg id proj _st]
  (sources/global-project-unlink-ops cfg tool id (project-skills-dir proj) skills-dir))

(defn project-ops [cfg st]
  (concat
   (trust-ops cfg)
   (mapcat (fn [[id proj]]
             (when (contains? (:for-tools proj) tool)
               (project-skill-ops cfg id proj st)))
           (:projects cfg))))

;; ---------------------------------------------------------------- entry

(defn plan [cfg st]
  (concat (settings-ops cfg)
          (mcp-ops cfg st)
          (common/project-scope-skip-ops cfg tool)
          (skill-ops cfg st)
          (memory-ops cfg)
          (project-ops cfg st)))

(defn present? [] (some? (u/which cli)))
