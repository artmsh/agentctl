(ns agentctl.adapters.dsh
  "DeepSeek Harness (`dsh`) adapter.

   The Harness keeps its user data under `$DSH_HOME`, falling back to
   `~/.dsh` — the same resolution the Harness itself does (`dsh-home-paths`),
   so agentctl and the Harness always read and write one directory. Its local
   skill provider scans a fixed set of roots:

   | rank | source          | path                       |
   |------|-----------------|----------------------------|
   | 100  | project dsh     | `<repo>/.dsh/skills`        |
   | 200  | project agents  | `<repo>/.agents/skills`     |
   | 400  | user dsh        | `<dshHome>/skills`          |
   | 500  | user agents     | `<agentsHome>/skills`       |

   A skill is a directory bundle (`<name>/SKILL.md`) or a flat `<name>.md`;
   symlinked bundles are canonicalized and read like real ones, which is what
   makes a user-wide skill a symlink into `<dshHome>/skills` here, exactly as
   every other adapter does it.

   A project's skills are the shared `<repo>/.agents/skills` directory — the
   same one codex, antigravity and kimi read — so one project install serves
   all of them, and the Harness's own `<repo>/.dsh/skills` is left to hand
   authorship. Instructions come from `<dshHome>/AGENTS.md` (then the shared
   `<agentsHome>/AGENTS.md`), so a user-wide memory file is a link there.

   Settings, MCP servers and model providers are not written: the Harness
   composes those from its Cordis profile patch layers
   (`<dshHome>/cordis.patch.yml` and `<dshHome>/profiles/<profile>/cordis.patch.yml`),
   which are the user's own live configuration. Declaring one for dsh is
   reported rather than silently ignored."
  (:require [agentctl.adapters.common :as common]
            [agentctl.plan :as plan]
            [agentctl.scope :as scope]
            [agentctl.sources :as sources]
            [agentctl.state :as state]
            [agentctl.util :as u]
            [clojure.string :as str]))

(def tool :dsh)
(def cli "dsh")

(defn resolve-home
  "The Harness home for a `DSH_HOME` value: a blank override is treated as
   unset, which is what the Harness itself does, and a configured home is still
   an absolute path — the Harness expands a leading tilde, and so does
   `u/abs-path`."
  [configured]
  (if (and configured (not (str/blank? configured)))
    (u/abs-path configured)
    (str u/home "/.dsh")))

(def home (resolve-home (System/getenv "DSH_HOME")))

(def skills-dir (str home "/skills"))
(def memory-file (str home "/AGENTS.md"))

(defn project-skills-dir
  "The Harness reads `<repo>/.agents/skills` — the directory the `skills` CLI
   fills and the one codex, antigravity and kimi read, so a project skill
   installed for any of them is read by the Harness too. The Harness's own
   `<repo>/.dsh/skills` root stays untouched: it is the project's hand-written
   skills directory, and agentctl does not own it."
  [proj] (str (:path proj) "/.agents/skills"))

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

(defn project-ops [cfg]
  (mapcat (fn [[id proj]]
            (when (contains? (:for-tools proj) tool)
              (sources/global-project-unlink-ops cfg tool id (project-skills-dir proj) skills-dir)))
          (:projects cfg)))

;; ---------------------------------------------------------------- unwritten kinds

(defn- patch-file
  "The Harness composes settings, MCP servers and model providers from patch
   layers, not from one file agentctl could edit by key path."
  []
  (u/tilde (str home "/cordis.patch.yml")))

(defn- not-written-op [kind id what]
  (plan/op {:action :noop :tool tool :kind kind :id id :warn true
            :summary (str what " — DeepSeek Harness composes it from "
                          (patch-file) ", which agentctl does not write")}))

(defn settings-ops
  "A declared dsh setting is reported, not dropped: the Harness has no per-key
   settings file, so writing nothing would read exactly like a converged
   setting."
  [cfg]
  (for [k (keys (common/settings-for cfg tool))]
    (not-written-op :settings k (str "setting " (u/kw->str k) " is not written for dsh"))))

(defn- legacy-project-settings-ops
  "A project-level dsh settings override in the legacy map form is dropped the
   same way the global one is; report it per key rather than folding it into
   the project's tool list. The entity form already warns once per row."
  [cfg]
  (when-not (:entity-dsl? cfg)
    (for [[pid proj] (:projects cfg)
          [t s] (:tools proj)
          :when (= t tool)
          k (keys s)]
      (assoc (not-written-op :settings (keyword (name pid) (name k))
                             (str "setting " (u/kw->str k) " for " (name pid)
                                  " is not written for dsh"))
             :project pid :project-path (:path proj)))))

(defn- explicit-tools
  "The tools a declaration names outright, aliases resolved. nil means \"every
   tool that supports the kind\" — the case normalization answers on its own."
  [decl]
  (let [ts (or (:tools decl) (:for decl) (:acli decl))]
    (when (and ts (not= :all ts))
      (set (scope/acli ts)))))

(defn unsupported-ops
  "MCP servers and providers a declaration names dsh for.

   Normalization keeps only the tools that support a kind, so by the time an
   adapter sees `:mcps`/`:providers` the dsh selection is already gone — the
   declaration itself is the only place left that knows the resource was asked
   for. Reporting it here keeps \"not written\" from looking like \"converged\"
   in the plan, and `config/structural-findings` says the same thing before
   planning."
  [cfg]
  (let [entity-ops
        (for [{:keys [kind id decl]} (:entities cfg)
              :when (#{:mcps :providers} kind)
              :when (contains? (or (explicit-tools decl) #{}) tool)]
          (not-written-op kind id
                          (str (if (= kind :mcps) "MCP server " "provider ") (name id)
                               " is not written for dsh")))
        legacy-ops
        (when-not (:entity-dsl? cfg)
          (for [[section kind] [[:mcps :mcps] [:extra-providers :providers] [:providers :providers]]
                [id decl] (get-in cfg [:raw section])
                :when (map? decl)
                :when (contains? (or (explicit-tools decl) #{}) tool)]
            (not-written-op kind id
                            (str (if (= kind :mcps) "MCP server " "provider ") (name id)
                                 " is not written for dsh"))))]
    (concat entity-ops legacy-ops)))

;; ---------------------------------------------------------------- entry

(defn plan [cfg st]
  (concat (settings-ops cfg)
          (legacy-project-settings-ops cfg)
          (unsupported-ops cfg)
          (skill-ops cfg st)
          (memory-ops cfg)
          (project-ops cfg)))

(defn present?
  "The desktop app always creates its home, and the CLI is not necessarily on
   PATH — a machine where the Harness has run is a machine worth provisioning,
   so an existing home (or a configured one) counts as installed."
  []
  (boolean (or (u/which cli)
               (u/exists? home)
               (let [configured (System/getenv "DSH_HOME")]
                 (and configured (not (str/blank? configured)))))))
