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
            [clojure.string :as str]))

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
  (or (= parent child) (str/starts-with? (str child) (str parent "/"))))

(defn- normal
  "Absolute, with `.` and `..` resolved by spelling alone."
  [p]
  (str (fs/normalize (fs/absolutize (str p)))))

(defn- canonical
  "`normal`, then every ancestor that exists through its real path — on macOS
   /tmp is /private/tmp, and two spellings of one place must compare equal.
   The last segment is never resolved: a symlink there is a file of its own."
  [p]
  (let [p (normal p)
        parent (fs/parent p)]
    (if-not parent
      p
      (loop [cur parent tail (list (str (fs/file-name p)))]
        (cond
          (fs/exists? cur) (str/join "/" (cons (str (fs/real-path cur)) tail))
          (nil? (fs/parent cur)) p
          :else (recur (fs/parent cur) (conj tail (str (fs/file-name cur)))))))))

(defn same-file?
  "Do two paths name one file, whatever the spelling of the directories?"
  [a b]
  (= (canonical a) (canonical b)))

(defn- canonical-dir
  "A directory with its own symlinks resolved too, when it exists."
  [d]
  (str (fs/parent (canonical (str d "/x")))))

(defn config-path
  "The file the renderer reads when neither `--config` nor
   `AGENT_STATUSLINE_CONFIG` names another: `$XDG_CONFIG_HOME/agent-statusline/config.json`,
   else `~/.config/agent-statusline/config.json`, spelled without `.` or `..`.

   An empty or relative `XDG_CONFIG_HOME` is no base directory (the XDG spec
   says to ignore one), and under `AGENTCTL_HOME` one that is not inside that
   sandbox — compared after resolving `..` and symlinked directories — is
   ignored too: a scratch run must not write into the real home."
  ([] (config-path (System/getenv)))
  ([env]
   (let [xdg (get env "XDG_CONFIG_HOME")
         sandbox (get env "AGENTCTL_HOME")
         base (when (and (not (str/blank? xdg)) (fs/absolute? xdg)) (normal xdg))
         usable? (and base (or (str/blank? sandbox)
                               (under? (canonical-dir u/home) (canonical-dir base))))]
     (str (if usable? base (str (normal u/home) "/.config")) "/agent-statusline/config.json"))))

(defn renderer-path
  "The file the renderer itself would read, run with `env`. It takes both
   variables as given — an empty `AGENT_STATUSLINE_CONFIG` is an explicit path
   it fails to read, an empty or relative `XDG_CONFIG_HOME` a base it uses —
   and `HOME` is `u/home`, so a sandboxed run compares like with like."
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

(defn- spelled
  "A keyword or symbol in full: a namespace is kept, never dropped, so
   `:x/auto` reaches the renderer as \"x/auto\" and is judged there."
  [x]
  (if (keyword? x) (subs (str x) 1) (str x)))

(defn json-key
  "`:context-style` -> \"context_style\". A string key is taken as written,
   apart from the same `-` -> `_`."
  [k]
  (str/replace (spelled k) "-" "_"))

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
    (or (keyword? v) (symbol? v)) (spelled v)
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

(defn- edn-key [k] (keyword (str/replace (u/key-str k) "_" "-")))

(defn- untranslate [v]
  (cond
    (map? v) (into {} (map (fn [[k x]] [(edn-key k) (untranslate x)])) v)
    (sequential? v) (mapv untranslate v)
    :else v))

(defn scan
  "The `:statusline` the file on disk implies, for `import`: keys back in
   kebab-case, values as written, so applying it writes the same settings.
   nil when there is no file or it holds no JSON object."
  []
  (let [d (try (some-> (u/slurp-safe (config-path)) json/parse-string)
               (catch Exception _ nil))]
    (when (map? d) (untranslate d))))

;; ---------------------------------------------------------------- --check

(defn- claude-renderer
  "The renderer a declared Claude `:status-line` command runs, when that
   command is `agent-statusline` by path — the common way it is set up."
  [cfg]
  (when-let [cmd (get-in cfg [:tools :claude :status-line :command])]
    (let [bin (some-> (first (u/split-cmd cmd)) u/expand u/abs-path)]
      (when (and bin (= "agent-statusline" (str (fs/file-name bin)))) bin))))

(defn renderer-bin
  "The renderer to check with: `:bin`, else `AGENT_STATUSLINE_BIN` (the
   variable the renderer's own adapters honour), else `agent-statusline` on
   PATH, else the one a Claude `:status-line` command names."
  [sl]
  (let [env-bin (System/getenv "AGENT_STATUSLINE_BIN")]
    (cond
      (:bin sl) {:path (u/abs-path (str (:bin sl))) :via ":statusline :bin"}
      (not (str/blank? env-bin)) {:path (u/abs-path env-bin) :via "AGENT_STATUSLINE_BIN"}
      :else (or (when-let [p (u/which "agent-statusline")] {:path p :via "PATH"})
                (when-let [p (:claude-bin sl)]
                  (when (fs/executable? p) {:path p :via "the Claude :status-line command"}))))))

(def ^:dynamic *timeout-ms* 10000)

(defn- run-check
  "`bin --check --config <text in a temp file>`. stdin is closed; the
   contract says the check reads none, and a binary that predates it must not
   hang waiting for a status payload."
  [bin text]
  (let [f (fs/create-temp-file {:prefix "agent-statusline-" :suffix ".json"})]
    (try
      (spit (fs/file f) text)
      (let [proc (p/process [bin "--check" "--config" (str f)] {:in "" :out :string :err :string})
            res (deref proc *timeout-ms* ::timeout)]
        (if (= ::timeout res)
          (do (p/destroy-tree proc) {:exit -1 :timeout true})
          {:exit (:exit res) :err (str/trim (or (:err res) ""))}))
      (catch Exception e {:exit -1 :err (.getMessage e)})
      (finally (fs/delete-if-exists f)))))

(defn- diagnostic [err]
  (let [line (last (remove str/blank? (str/split-lines (str err))))]
    (if line (str/replace line #"^agent-statusline:\s*" "") "no diagnostic")))

(def ^:private checked (atom {}))

(def ^:private probe-invalid
  "A document the contract rejects whatever the renderer's version: its
   objects are closed, so an unknown top-level key exits 1."
  "{\"agentctl_probe\": true}\n")

(defn check
  "What the renderer says about the declared JSON:
     {:status :ok}                      it accepts it
     {:status :invalid :message …}      it rejects it (exit 1)
     {:status :unknown :message …}      it could not be asked
   Before the candidate, two probes: `{}` must pass and a document with an
   unknown key must fail with 1. A binary that answers otherwise does not
   implement `--check` — one built before it rejects everything, and
   something like `true` accepts everything — so that is unknown, never a
   verdict on the config. Remembered per binary, mtime and text: findings
   and the plan ask the same question in one run."
  [sl]
  (let [{:keys [path via]} (renderer-bin sl)
        unknown (fn [msg] {:status :unknown :message (str msg " — the config is not validated")})
        timed-out (fn [] (unknown (str (u/tilde path) " --check timed out after "
                                       (quot *timeout-ms* 1000) "s")))]
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
            empty-ok (ask "{}\n")
            bad (when (and (not (:timeout empty-ok)) (zero? (:exit empty-ok))) (ask probe-invalid))]
        (cond
          (or (:timeout empty-ok) (:timeout bad)) (timed-out)

          (not (zero? (:exit empty-ok)))
          (unknown (str (u/tilde path) " does not implement --check (it rejects an empty config: "
                        (diagnostic (:err empty-ok)) ") — rebuild it"))

          (not= 1 (:exit bad))
          (unknown (str (u/tilde path) " does not implement --check (it "
                        (if (zero? (:exit bad)) "accepts" (str "exits " (:exit bad) " on"))
                        " a config with an unknown key)"))

          :else
          (let [{:keys [exit err timeout]} (ask (:text sl))]
            (cond
              timeout (timed-out)
              (zero? exit) {:status :ok :message (str "accepted by " (u/tilde path) " --check")}
              (= 1 exit) {:status :invalid :message (str "agent-statusline rejects it: " (diagnostic err))}
              :else (unknown (str (u/tilde path) " --check failed (exit " exit "): " (diagnostic err))))))))))

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

(defn selected?
  "Does a run with these options plan the file? `-k statusline` asks for it
   by name, so `-t` — which names CLIs, none of which owns this file — does
   not take it away again. A run narrowed to projects, or aimed at a host,
   never touches it."
  [{:keys [tools kinds projects host]}]
  (let [named? (contains? (set kinds) kind)]
    (boolean (and (not host) (empty? projects)
                  (or (empty? kinds) named?)
                  (or (empty? tools) named?)))))

(defn env-finding
  "The file the renderer would read here, when it is not the one agentctl
   writes."
  [env writes]
  (let [explicit (get env "AGENT_STATUSLINE_CONFIG")
        reads (renderer-path env)]
    (cond
      (= "" explicit)
      (finding :warn [:env] (str "AGENT_STATUSLINE_CONFIG is set but empty: the renderer fails"
                                 " to read any config (FileNotFound) and never reads " (u/tilde writes)))
      (not (same-file? reads writes))
      (finding :warn [:env] (str "the renderer, run with this environment, reads " (u/tilde reads)
                                 " — agentctl writes " (u/tilde writes))))))

(defn- with-claude [cfg]
  (some-> (:statusline cfg) (assoc :claude-bin (claude-renderer cfg))))

(defn findings
  "What this machine says about `:statusline`: the renderer's verdict, the
   file the renderer would read here, and awl's build. Local runs only — a
   host is not provisioned with the file, and none of this describes it.
   `apply` refuses on an error among them, but only when the run would write
   the file: one narrowed by `-t`, `-k` or to projects gets a warning."
  ([cfg] (findings cfg {}))
  ([cfg opts]
   (let [sl (with-claude cfg)
         soften (fn [f] (if (and (= :error (:level f)) (not (selected? opts)))
                          (assoc f :level :warn :message (str (:message f) " (this run does not write it)"))
                          f))]
     (when (and sl (not (:problem sl)) (not (:clashes sl)))
       (map soften
            (concat
             (let [{:keys [status message]} (check sl)]
               [(finding (case status :ok :ok :invalid :error :unknown) [:config]
                         (if (= :invalid status) (str message " — nothing is written") message))])
             (keep identity [(env-finding (System/getenv) (config-path))])
             ;; a `:warn`, not `:unknown`, when awl cannot be read: `apply`
             ;; prints warnings on every run, and an override awl may never
             ;; honour must not disappear behind "no changes" once the file is
             ;; written
             (when (:awl? sl)
               (let [[level message] (awl-status)]
                 [(finding level [:awl] message)]))))))))

;; ---------------------------------------------------------------- plan

(defn- parse [text]
  (try (json/parse-string text) (catch Exception _ ::unparseable)))

(defn- backup!
  "`u/backup!` copies a symlink as a link, which would leave the backup
   pointing at the very file about to change. A link is backed up as the
   bytes it resolves to."
  [target]
  (if (and (fs/sym-link? target) (fs/exists? target))
    (let [dest (fs/path (u/backup-dir) (str/replace (u/tilde target) #"[/~]" "_"))]
      (when-not (fs/exists? dest {:nofollow-links true})
        (fs/create-dirs (fs/parent dest))
        (fs/copy (fs/real-path target) dest))
      (str dest))
    (u/backup! target)))

(defn- owned-here?
  "Ownership is of a path: a file agentctl wrote elsewhere says nothing
   about the one at `target`."
  [owned target]
  (boolean (and owned (same-file? (or (:path owned) target) target))))

(defn- write-op [sl owned]
  (let [target (config-path)
        link (when (fs/sym-link? target) (str (fs/read-link target)))
        current (u/slurp-safe target)
        exists? (u/exists? target)
        mine? (owned-here? owned target)
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
                          [(when link
                             (str "replaces the symlink to " (u/tilde link) " with a regular file;"
                                  " the file it points to is left as it is (its content backed up)"))
                           (when (and exists? (not mine?) (not link))
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
                  :diffs [{:key id :before (when (map? before) before) :after (:data sl)}]
                  :risk (if (and exists? (or link (not mine?))) :medium :low)
                  ;; never through a link: agentctl writes the declared path
                  ;; and nothing outside it
                  :exec! (fn []
                           (backup! target)
                           (when (fs/sym-link? target) (fs/delete target))
                           (u/write-text! target (:text sl)))})))))

(defn- prune-op
  "Only a file agentctl wrote is removed, at the path it was written to. A
   symlink there now is someone else's, and stays."
  [owned why]
  (let [target (or (:path owned) (config-path))
        current (u/slurp-safe target)]
    (cond
      (fs/sym-link? target)
      (plan/op {:action :noop :warn true :tool tool :kind kind :id id :target target
                :summary (str (u/tilde target) " is a symlink now, not the file agentctl wrote — left in place")})

      :else
      (when-let [o (plan/unlink-op {:tool tool :kind kind :id id :dest target})]
        (assoc o
               :summary (str why " — delete " (u/tilde target))
               :note (when (and current (:sha256 owned) (not= (:sha256 owned) (u/sha256 current)))
                       "edited since agentctl wrote it (backed up first)"))))))

(defn plan-ops
  "The write, the removal of the file agentctl owns, or both when the target
   moved (XDG_CONFIG_HOME changed): the old file is agentctl's and goes, the
   new path is judged on its own. See `selected?` for which runs plan it."
  [cfg st opts]
  (when (selected? opts)
    (let [sl (with-claude cfg)
          owned (state/entry st tool kind id)]
      (remove nil?
              (cond
                (:problem sl) nil
                sl [(write-op sl owned)
                    (when (and owned (not (owned-here? owned (config-path))))
                      (prune-op owned (str "moved to " (u/tilde (config-path)))))]
                owned [(prune-op owned "removed from agents.edn")])))))

(defn inventory
  "The ownership record: where the file went, and a digest of what was
   written, so a later removal can say the file was edited since."
  [cfg]
  (when-let [{:keys [text clashes]} (:statusline cfg)]
    (when (and text (not clashes))
      [[tool kind id {:path (config-path) :sha256 (u/sha256 text)}]])))
