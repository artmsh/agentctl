(ns agentctl.statusline
  "The shared status-line config: one JSON file that an external renderer,
   `agent-statusline`, reads for every agent it draws a status line for.

   agentctl owns the whole file. The EDN under `:statusline` is that JSON
   spelled as EDN — keys kebab-case, enum values as keywords — and the
   translation is mechanical: agentctl knows no option, enum or threshold.
   The renderer's own `--check` decides what is valid, so the rules live in
   one place, and a key agentctl has never heard of is written as given and
   judged there."
  (:require [agentctl.plan :as plan]
            [agentctl.state :as state]
            [agentctl.util :as u]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(def tool
  "The file belongs to no single CLI, so its ops are agentctl's own: `-t`
   never selects them, `-k statusline` does."
  :agentctl)
(def kind :statusline)
(def id :config)

(def ^:private own-keys
  "Keys under `:statusline` that tell agentctl something and are never written."
  #{:bin})

;; ---------------------------------------------------------------- where

(defn- under? [parent child]
  (let [parent (str/replace (str parent) #"/+$" "")]
    (or (= parent child) (str/starts-with? (str child) (str parent "/")))))

(defn config-path
  "The file the renderer reads when neither `--config` nor
   `AGENT_STATUSLINE_CONFIG` names another: `$XDG_CONFIG_HOME/agent-statusline/config.json`,
   else `~/.config/agent-statusline/config.json`.

   An empty or relative `XDG_CONFIG_HOME` is no base directory (the XDG spec
   says to ignore one), and under `AGENTCTL_HOME` one outside that sandbox is
   ignored too: a scratch run must not write into the real home."
  ([] (config-path (System/getenv)))
  ([env]
   (let [xdg (get env "XDG_CONFIG_HOME")
         sandbox (get env "AGENTCTL_HOME")
         usable? (and (not (str/blank? xdg))
                      (fs/absolute? xdg)
                      (or (str/blank? sandbox) (under? u/home xdg)))]
     (str (if usable? (str/replace xdg #"/+$" "") (str u/home "/.config"))
          "/agent-statusline/config.json"))))

(defn renderer-path
  "The file the renderer itself would read, run with `env`. It takes
   `XDG_CONFIG_HOME` as given, empty or relative, and `HOME` is `u/home`, so
   a sandboxed run compares like with like."
  ([] (renderer-path (System/getenv)))
  ([env]
   (let [explicit (get env "AGENT_STATUSLINE_CONFIG")
         xdg (get env "XDG_CONFIG_HOME")]
     (cond
       (some? explicit) explicit
       (some? xdg) (str xdg "/agent-statusline/config.json")
       :else (str u/home "/.config/agent-statusline/config.json")))))

;; ---------------------------------------------------------------- EDN -> JSON

(def ^:private layout
  "The order the renderer documents its keys in. Layout only — a key outside
   this list is still written, after these, and the renderer judges it."
  ["defaults" "agents"
   "segments" "separator" "color" "context_style" "short_model" "show_pacing"
   "warning_percent" "critical_percent"
   "cc" "codex" "awl" "pi"])

(defn json-key
  "`:context-style` -> \"context_style\". A string key is taken as written,
   apart from the same `-` -> `_`."
  [k]
  (str/replace (if (keyword? k) (name k) (str k)) "-" "_"))

(defn- ordered [m]
  (let [rank (zipmap layout (range))]
    (apply array-map (mapcat identity (sort-by (fn [[k _]] [(get rank k (count layout)) k]) m)))))

(defn- translate
  "Map keys to JSON spelling, keyword and symbol values to strings, every
   other value as it is — `nil` included, which in an agent's override means
   `inherit the default`. Two EDN keys that land on one JSON key are recorded
   in `clashes`: the JSON would keep one of them silently."
  [v where clashes]
  (cond
    (map? v)
    (let [groups (group-by (comp json-key key) v)]
      (doseq [[jk entries] groups :when (next entries)]
        (vswap! clashes conj {:where (conj where jk) :keys (sort-by str (map key entries))}))
      (ordered (into {} (for [[jk [[_ x]]] groups] [jk (translate x (conj where jk) clashes)]))))
    (or (keyword? v) (symbol? v)) (name v)
    (coll? v) (mapv #(translate % where clashes) v)
    :else v))

(defn normalize
  "nil when `:statusline` is absent. Otherwise the JSON it stands for and the
   exact text agentctl writes — validation and convergence both work on those
   bytes, so what was checked is what lands on disk."
  [decl]
  (cond
    (nil? decl) nil
    (not (map? decl))
    {:problem ":statusline must be a map, e.g. {:defaults {:segments [:model :context]}}"}
    :else
    (let [clashes (volatile! [])
          data (translate (apply dissoc decl own-keys) [] clashes)
          agents (get data "agents")]
      (u/prune-nils
       {:bin (:bin decl)
        :data data
        :text (str (json/generate-string data {:pretty true}) "\n")
        :clashes (not-empty @clashes)
        ;; awl reads the file but is built to, or not: see `awl-status`
        :awl? (and (map? agents) (contains? agents "awl"))}))))

;; ---------------------------------------------------------------- --check

(defn renderer-bin
  "The renderer to check with: `:bin`, else `AGENT_STATUSLINE_BIN` (the
   variable the renderer's own adapters honour), else `agent-statusline` on
   PATH."
  [sl]
  (let [env-bin (System/getenv "AGENT_STATUSLINE_BIN")]
    (cond
      (:bin sl) {:path (u/abs-path (str (:bin sl))) :via ":statusline :bin"}
      (not (str/blank? env-bin)) {:path (u/abs-path env-bin) :via "AGENT_STATUSLINE_BIN"}
      :else (when-let [p (u/which "agent-statusline")] {:path p :via "PATH"}))))

(defn- run-check
  "`bin --check --config <text in a temp file>`. stdin is closed; the
   contract says the check reads none, and a binary that predates it must not
   hang waiting for a status payload."
  [bin text]
  (let [f (fs/create-temp-file {:prefix "agent-statusline-" :suffix ".json"})]
    (try
      (spit (fs/file f) text)
      (let [proc (p/process [bin "--check" "--config" (str f)] {:in "" :out :string :err :string})
            res (deref proc 10000 ::timeout)]
        (if (= ::timeout res)
          (do (p/destroy-tree proc) {:exit -1 :err "timed out after 10s"})
          {:exit (:exit res) :err (str/trim (or (:err res) ""))}))
      (catch Exception e {:exit -1 :err (.getMessage e)})
      (finally (fs/delete-if-exists f)))))

(defn- diagnostic [err]
  (let [line (last (remove str/blank? (str/split-lines (str err))))]
    (if line (str/replace line #"^agent-statusline:\s*" "") "no diagnostic")))

(def ^:private checked (atom {}))

(defn check
  "What the renderer says about the declared JSON:
     {:status :ok}                      it accepts it
     {:status :invalid :message …}      it rejects it (exit 1)
     {:status :unknown :message …}      it could not be asked
   A binary that rejects the empty config `{}` does not implement `--check`
   (one built before it answers UnknownArgument) — that is unknown, never a
   verdict on the config. Remembered per binary and text: findings and the
   plan ask the same question in one run."
  [sl]
  (let [{:keys [path via]} (renderer-bin sl)
        unknown (fn [msg] {:status :unknown :message (str msg " — the config is not validated")})]
    (cond
      (nil? path)
      (unknown "agent-statusline is not on PATH (set :bin under :statusline, or AGENT_STATUSLINE_BIN)")

      (not (and (fs/regular-file? path) (fs/executable? path)))
      (unknown (str (u/tilde path) " (" via ") is not an executable file"))

      :else
      (let [;; the binary's mtime in the key: a renderer rebuilt under a
            ;; running GUI is a different renderer
            k (fn [text] [path (str (fs/last-modified-time path)) text])
            ask (fn [text] (or (get @checked (k text))
                               (let [r (run-check path text)] (swap! checked assoc (k text) r) r)))
            probe (ask "{}\n")]
        (cond
          (not (zero? (:exit probe)))
          (unknown (str (u/tilde path) " does not implement --check (it rejects an empty config: "
                        (diagnostic (:err probe)) ") — rebuild it"))

          :else
          (let [{:keys [exit err]} (ask (:text sl))]
            (case (long exit)
              0 {:status :ok :message (str "accepted by " (u/tilde path) " --check")}
              1 {:status :invalid :message (str "agent-statusline rejects it: " (diagnostic err))}
              (unknown (str (u/tilde path) " --check failed (exit " exit "): " (diagnostic err))))))))))

;; ---------------------------------------------------------------- awl

(def awl-marker
  "A string the renderer's config loader puts into every binary it is linked
   into. awl links that loader only when built with `-Dstatusline`, and has no
   flag that says so — `--help` reads the same either way — so the binary is
   read, never run."
  "AGENT_STATUSLINE_CONFIG")

(defn- native? [^bytes bs]
  (let [b #(bit-and (aget bs %) 0xff)
        head (when (>= (alength bs) 4) (mapv b (range 4)))]
    (boolean
     (or (= head [0x7f 0x45 0x4c 0x46])                    ; ELF
         (#{[0xcf 0xfa 0xed 0xfe] [0xce 0xfa 0xed 0xfe]     ; Mach-O
            [0xfe 0xed 0xfa 0xcf] [0xfe 0xed 0xfa 0xce]
            [0xca 0xfe 0xba 0xbe]} head)                    ; universal
         (and head (= (subvec head 0 2) [0x4d 0x5a]))))))   ; PE

(def ^:private scanned (atom {}))

(defn awl-build
  ":statusline, :plain, or :opaque for something that is not a native
   executable (a wrapper script), which cannot be read this way."
  [path]
  (let [k [path (fs/size path) (str (fs/last-modified-time path))]]
    (or (get @scanned k)
        (let [bs (fs/read-all-bytes path)
              r (cond
                  (not (native? bs)) :opaque
                  (str/includes? (String. ^bytes bs "ISO-8859-1") awl-marker) :statusline
                  :else :plain)]
          (swap! scanned assoc k r)
          r))))

(defn awl-status
  "[level message] for an `:awl` override. awl reads the shared file itself,
   so the file is all agentctl can deliver; whether awl uses it is fixed when
   awl is built. Only a build that is seen to include the renderer is `:ok`."
  []
  (let [target (u/tilde (config-path))
        path (u/which "awl")]
    (if-not path
      [:warn (str "awl is not on PATH, so whether it was built with -Dstatusline is unknown;"
                  " only such a build reads " target)]
      (case (try (awl-build path) (catch Exception _ :opaque))
        :statusline [:ok (str (u/tilde path) " was built with -Dstatusline and reads " target
                              " at startup")]
        :plain [:warn (str (u/tilde path) " was built without -Dstatusline: it keeps its own"
                           " status row and never reads " target " — rebuild it with"
                           " -Dstatusline=<renderer source>")]
        [:warn (str (u/tilde path) " is not a native executable, so whether awl was built with"
                    " -Dstatusline is unknown; only such a build reads " target)]))))

;; ---------------------------------------------------------------- findings

(defn- finding [level where message]
  {:level level :where (into [:statusline] where) :message message})

(defn shape-findings
  "What is wrong with `:statusline` as written, wherever agentctl runs: part
   of the structural check every command makes, `--host` included."
  [cfg]
  (when-let [sl (:statusline cfg)]
    (if-let [problem (:problem sl)]
      [(finding :error [] problem)]
      (for [{:keys [where keys]} (:clashes sl)]
        (finding :error where (str (str/join " and " (map pr-str keys)) " both name `"
                                   (last where) "` in the JSON — keep one"))))))

(defn findings
  "What this machine says about `:statusline`: the renderer's verdict, the
   file the renderer would read here, and awl's build. Local runs only — a
   host is not provisioned with the file, and none of this describes it.
   `validate` reports these, and `apply` refuses on an error among them."
  [cfg]
  (let [sl (:statusline cfg)]
    (when (and sl (not (:problem sl)) (not (:clashes sl)))
      (concat
       (let [{:keys [status message]} (check sl)]
         [(finding (case status :ok :ok :invalid :error :unknown) [:config]
                   (if (= :invalid status) (str message " — nothing is written") message))])
       (let [writes (config-path) reads (renderer-path)]
         (when (not= (u/abs-path reads) writes)
           [(finding :warn [:env]
                     (str "the renderer, run with this environment, reads " (u/tilde reads)
                          " — agentctl writes " (u/tilde writes)))]))
       ;; a `:warn`, not `:unknown`, when awl cannot be read: `apply` prints
       ;; warnings on every run, and an override awl may never honour must not
       ;; disappear behind "no changes" once the file is written
       (when (:awl? sl)
         (let [[level message] (awl-status)]
           [(finding level [:awl] message)]))))))

;; ---------------------------------------------------------------- plan

(defn- parse [text]
  (try (json/parse-string text) (catch Exception _ ::unparseable)))

(def ^:private inherit
  "How a `null` reads in the plan. In an agent's override it is the way to say
   \"inherit the default\", and the generic diff drops a nil that has nothing to
   differ from — the override would preview as an empty agent."
  (symbol "null (inherit)"))

(defn- shown
  "`m` as the plan shows it: every null value spelled out."
  [m]
  (walk/postwalk (fn [x] (if (map? x) (into (empty x) (map (fn [[k v]] [k (if (nil? v) inherit v)])) x) x)) m))

(defn- write-op [sl owned]
  (let [target (config-path)
        current (u/slurp-safe target)
        exists? (u/exists? target)
        before (some-> current parse)
        {:keys [status message]} (check sl)]
    (cond
      (or (:clashes sl) (= :invalid status))
      (plan/op {:action :noop :warn true :tool tool :kind kind :id id :target target
                :summary (str "not written — " (or message "conflicting keys; see validate"))})

      (= current (:text sl))
      (plan/op {:action :noop :tool tool :kind kind :id id :target target})

      :else
      (let [notes (remove nil?
                          [(when (and exists? (not owned))
                             "replaces a file agentctl did not write (backed up first)")
                           (when (= ::unparseable before) "the file on disk is not valid JSON")
                           (when (and (map? before) (= (u/norm before) (u/norm (:data sl))))
                             "same settings — rewritten in agentctl's layout")
                           (when (= :unknown status) message)])]
        (plan/op {:action (if exists? :update :create)
                  :tool tool :kind kind :id id :target target
                  :summary (str "write " (u/tilde target))
                  :note (when (seq notes) (str/join "; " notes))
                  ;; a whole file is the change: show it on create too
                  :preview true
                  :diffs [{:key id :before (when (map? before) (shown before)) :after (shown (:data sl))}]
                  :risk (if (and exists? (not owned)) :medium :low)
                  :exec! (fn []
                           (u/backup! target)
                           (u/write-text! target (:text sl)))})))))

(defn- prune-op
  "Only a file agentctl wrote is removed, at the path it was written to."
  [owned]
  (let [target (or (:path owned) (config-path))
        current (u/slurp-safe target)]
    (when-let [o (plan/unlink-op {:tool tool :kind kind :id id :dest target})]
      (assoc o
             :summary (str "removed from agents.edn — delete " (u/tilde target))
             :note (when (and current (:sha256 owned) (not= (:sha256 owned) (u/sha256 current)))
                     "edited since agentctl wrote it (backed up first)")))))

(defn plan-ops
  "The write, or the removal of a file agentctl owns. `-t` names CLIs, and
   this file belongs to none of them, so a run narrowed by `-t` leaves it out."
  [cfg st opts]
  (when (empty? (:tools opts))
    (let [sl (:statusline cfg)
          owned (state/entry st tool kind id)]
      (remove nil?
              [(cond
                 (:problem sl) nil
                 sl (write-op sl owned)
                 owned (prune-op owned))]))))

(defn inventory
  "The ownership record: where the file went, and a digest of what was
   written, so a later removal can say the file was edited since."
  [cfg]
  (when-let [{:keys [text clashes]} (:statusline cfg)]
    (when (and text (not clashes))
      [[tool kind id {:path (config-path) :sha256 (u/sha256 text)}]])))
