(ns agentctl.gui
  "Loopback GUI: edit agents.edn on one side, watch the dry run on the other.

   The page is served to a browser because that is the only toolkit a babashka
   script can count on. Everything it can do, the CLI can do — `/api/plan` is
   `apply` and `/api/apply` is `apply!`, planning from the *buffer* rather than
   from the file so a change is answered before it is saved.

   The server writes files, so it is treated as a capability, not a page: it
   binds to loopback only, checks the Host header, and every call carries a
   per-run token that is handed out exactly once, in the URL it prints."
  (:require [agentctl.config :as config]
            [agentctl.core :as core]
            [agentctl.edit :as edit]
            [agentctl.form :as form]
            [agentctl.plan :as plan]
            [agentctl.state :as state]
            [agentctl.util :as u]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http])
  (:import [java.security SecureRandom]))

;; ---------------------------------------------------------------- opts

(defn opts-from-json
  "JSON gives strings; every filter downstream compares keywords, and
   `keep-projects` does no coercion of its own — a vector of strings there
   would silently select nothing."
  [m base]
  (merge base
         {:tools (set (map keyword (:tools m)))
          :kinds (set (map keyword (:kinds m)))
          :projects (set (map keyword (:projects m)))
          :verbose (boolean (:verbose m))
          :show-noop (boolean (:show-noop m))}))

(defn- finding->data [f]
  {:level (name (:level f))
   :where (str/join " " (map u/kw->str (:where f)))
   :message (:message f)})

;; ---------------------------------------------------------------- planning

(defn- buffer-text
  "An empty buffer is the empty config, not a parse error: `gui` on a machine
   with no agents.edn yet opens on a blank page, and that page has to plan."
  [text]
  (if (str/blank? text) "{}" text))

(defn plan-for-text
  "Dry-run the buffer. Never throws: bad EDN is the normal state of a file
   being typed into, and the answer to it is a message, not a stack trace."
  [text path opts]
  (try
    (let [cfg (config/parse-config (buffer-text text) path)
          findings (config/structural-findings cfg)
          errors (filter #(= :error (:level %)) findings)]
      (if (seq errors)
        {:ok false
         :error "config errors — refusing to plan"
         :findings (mapv finding->data findings)}
        (let [st (state/load-state)
              ops (core/build-plan cfg st opts)
              changes (filter plan/mutating? ops)]
          {:ok true
           :findings (mapv finding->data findings)
           :missing (mapv name (core/missing-tools cfg opts))
           ;; the rendered plan, not the ops: `render-val` is where a
           ;; credential read out of an existing config file gets masked, and
           ;; serializing `:diffs` would route around it
           :plan (binding [plan/*color* false]
                   (if (seq changes)
                     (str/triml (plan/render-plan ops opts))
                     "no changes — environment matches agents.edn"))
           ;; the same plan as a table: grouped and masked in Clojure, because
           ;; both the grouping and `render-val`'s masking are decisions the
           ;; browser must not be left to re-derive. `:plan` stays for copy,
           ;; and for parity with what the CLI prints
           :ops (plan/plan-data ops opts)
           :summary (plan/summary-line ops)
           :changes (count changes)
           :secrets (count (filter #(= :secret (:risk %)) changes))})))
    (catch Exception e
      {:ok false :error (ex-message e) :findings []})))

;; ---------------------------------------------------------------- apply

(def ^:private apply-seq (atom 0))

(defn- backup-root-for-run
  "A long-lived process breaks `backup!`'s assumption of one run per process:
   its stamp is a per-process delay and the first copy of a file wins, so a
   second apply would find its backup slot taken and preserve nothing. Each
   apply gets a directory of its own."
  []
  (str u/*backup-root* "/gui-" (u/timestamp) "-" (swap! apply-seq inc)))

(defn apply-for-text!
  "Converge onto the buffer, then leave the buffer on disk — a plan applied
   from text that was never saved would show up as drift on the next CLI run.

   `:expect` is the summary line the user was looking at when they pressed the
   button. The environment can move underneath a plan, so a plan that no longer
   says what it said is refused rather than applied."
  [text path {:keys [expect] :as opts}]
  (let [pre (plan-for-text text path opts)]
    (cond
      (not (:ok pre))
      (assoc pre :status 400)

      (and expect (not= expect (:summary pre)))
      (assoc pre :status 409 :stale true
             :error "the environment changed since this plan was shown — review it and apply again")

      :else
      (binding [u/*backup-root* (backup-root-for-run)]
        (let [text (buffer-text text)
              cfg (config/parse-config text path)
              st (state/load-state)
              ops (core/build-plan cfg st opts)
              changes (filter plan/mutating? ops)
              config-backup (when (u/exists? path) (u/backup! path))]
          (u/write-text! path text)
          (if (empty? changes)
            (do (state/save! (core/sync-state! st cfg #{} (when (core/scoped? opts) #{})))
                (merge pre {:status 200 :applied 0 :failed []
                            :saved (u/tilde path)
                            :config-backup (some-> config-backup u/tilde)
                            :backups (u/tilde (u/backup-dir))}))
            (let [{:keys [done failed]} (core/converge! cfg st opts ops)]
              ;; ownership is never claimed for a resource whose creation
              ;; failed — a later prune would delete something we never made;
              ;; on a scoped run (-t/-k/-p) a data-carrying id (hooks) only
              ;; gets its value overwritten if this run actually wrote it —
              ;; see `core/sync-state!`
              (state/save! (core/sync-state! st cfg
                                              (set (map (juxt :tool :kind :id) failed))
                                              (when (core/scoped? opts)
                                                (set (map (juxt :tool :kind :id) done)))))
              (merge pre
                     {:status (if (seq failed) 500 200)
                      :applied (count done)
                      :saved (u/tilde path)
                      :config-backup (some-> config-backup u/tilde)
                      :backups (u/tilde (u/backup-dir))
                      :failed (mapv (fn [f] {:tool (name (:tool f))
                                             :kind (name (:kind f))
                                             :id (u/kw->str (:id f))
                                             :error (:error f)})
                                    failed)}))))))))

(defn- with-form
  "The controls and the dry run answer the same buffer, so they travel together
   — a form built from a later request could describe text the plan never saw."
  [text res]
  (assoc res :form (form/model (buffer-text text))))

;; ---------------------------------------------------------------- http

(defn- token []
  (let [b (byte-array 24)]
    (.nextBytes (SecureRandom.) b)
    (apply str (map #(format "%02x" %) b))))

(defn- json-res [status body]
  {:status status
   :headers {"content-type" "application/json; charset=utf-8"
             "cache-control" "no-store"}
   :body (json/generate-string body)})

(defn- loopback-host?
  "Guards against DNS rebinding: a page on another origin can reach 127.0.0.1,
   but only by sending some other name in the Host header."
  [host]
  (let [h (str/lower-case (first (str/split (str host) #":")))]
    (contains? #{"127.0.0.1" "localhost" "[::1]" "::1" ""} h)))

(defn- query-param [qs k]
  (some (fn [pair]
          (let [[a b] (str/split pair #"=" 2)]
            (when (= a k) b)))
        (str/split (or qs "") #"&")))

(defn- body-json [req]
  (some-> (:body req) slurp not-empty (json/parse-string true)))

(def ^:private page (delay (slurp (io/resource "agentctl/gui/index.html"))))

(defn- index-page [tok path]
  (-> @page
      (str/replace "__TOKEN__" tok)
      (str/replace "__CONFIG_PATH__" (u/tilde path))))

(defn handler
  "One request. `state` carries the token, the config path and the base opts
   the command line asked for."
  [{:keys [tok path base-opts lock]} req]
  (let [authed? (or (= tok (get-in req [:headers "x-agentctl-token"]))
                    (= tok (query-param (:query-string req) "t")))]
    (cond
      (not (loopback-host? (get-in req [:headers "host"])))
      {:status 403 :body "agentctl gui serves 127.0.0.1 only"}

      (not authed?)
      {:status 403 :headers {"content-type" "text/plain"}
       :body "forbidden — open the URL agentctl printed, it carries this run's token\n"}

      :else
      (case [(:request-method req) (:uri req)]
        [:get "/"]
        {:status 200
         :headers {"content-type" "text/html; charset=utf-8" "cache-control" "no-store"}
         :body (index-page tok path)}

        [:get "/api/config"]
        (json-res 200 {:path (u/tilde path)
                       :exists (boolean (u/exists? path))
                       :text (or (u/slurp-safe path) "")
                       :tools (mapv name config/all-tools)
                       :opts {:tools (mapv name (:tools base-opts))
                              :kinds (mapv name (:kinds base-opts))
                              :projects (mapv name (:projects base-opts))
                              :verbose (boolean (:verbose base-opts))
                              :show-noop (boolean (:show-noop base-opts))}})

        [:post "/api/plan"]
        (let [b (body-json req)]
          ;; serialized against apply: a plan rendered from a half-converged
          ;; environment would be a plan of nothing in particular
          (locking lock
            (json-res 200 (with-form (:text b)
                            (plan-for-text (:text b) path (opts-from-json b base-opts))))))

        [:post "/api/edit"]
        ;; the controls change one field at a time; the answer is the new
        ;; buffer and what it would do — never a write
        (let [b (body-json req)]
          (try
            (let [text (edit/apply-ops (buffer-text (:text b)) (form/ops->edits (:ops b)))]
              (locking lock
                (json-res 200 (assoc (with-form text
                                       (plan-for-text text path (opts-from-json b base-opts)))
                                     :text text))))
            (catch Exception e
              (json-res 400 {:ok false :error (ex-message e)}))))

        [:post "/api/apply"]
        (let [b (body-json req)]
          (if-not (true? (:confirm b))
            ;; the browser dialog is a courtesy; this is the gate
            (json-res 400 {:ok false :error "apply requires an explicit confirmation"})
            (locking lock
              (let [res (apply-for-text! (:text b) path
                                         (assoc (opts-from-json b base-opts) :expect (:expect b)))]
                (json-res (:status res 200) (dissoc res :status))))))

        [:get "/api/state"]
        (json-res 200 {:path (u/tilde state/path)
                       :managed (mapv (fn [[[t k id] _]] {:tool (name t) :kind (name k) :id (str id)})
                                      (sort-by (comp str key) (:managed (state/load-state))))})

        {:status 404 :body "not found"}))))

;; ---------------------------------------------------------------- entry

(defn- open-browser! [url]
  (let [cmd (case (str/lower-case (or (System/getProperty "os.name") ""))
              ("mac os x" "darwin") "open"
              (if (u/which "xdg-open") "xdg-open" nil))]
    (when cmd (future (u/sh cmd url)))))

(defn serve!
  "Start the server. Returns {:stop :port :url}; `run-server` does not block,
   so the caller has to."
  [{:keys [port path open?] :or {port 0 open? true} :as opts}]
  (let [tok (token)
        path (u/abs-path (or path config/default-path))
        ctx {:tok tok :path path :base-opts opts :lock (Object.)}
        stop (http/run-server #(handler ctx %) {:port port :ip "127.0.0.1" :legacy-return-value? false})
        port (http/server-port stop)
        url (str "http://127.0.0.1:" port "/?t=" tok)]
    (when open? (open-browser! url))
    {:stop #(http/server-stop! stop) :port port :url url}))

(defn run!
  "The `gui` command: serve until interrupted."
  [opts]
  (let [{:keys [url]} (serve! opts)]
    (println (str "agentctl gui — " (u/tilde (u/abs-path (or (:path opts) config/default-path)))))
    (println (str "  " url))
    (println "  the URL carries this run's token; anyone without it gets a 403")
    (println "\nCtrl-C to stop.")
    @(promise)))
