(ns agentctl.util.schema)

(defn expand-fn-check
  "The nil case is preserved: a nil input returns nil, while a string input
   always returns a string."
  [[args ret]]
  (= (nil? (first args)) (nil? ret)))

(def expand
  [:=> [:cat [:maybe :string]]
   [:maybe :string]
   [:fn expand-fn-check]])

(defn nil-input-nil-output-check
  "Nil paths short-circuit the safe readers and path helpers."
  [[args ret]]
  (or (some? (first args)) (nil? ret)))

(def path-sha256
  [:=> [:cat [:maybe :string]]
   [:maybe [:re #"^[0-9a-f]{64}$"]]
   [:fn nil-input-nil-output-check]])

(def JsonValue
  [:schema
   {:registry {::json-value
               [:or :nil :boolean number? :string
                [:sequential [:ref ::json-value]]
                [:map-of :keyword [:ref ::json-value]]]}}
   [:ref ::json-value]])

(def read-json
  [:=> [:cat [:maybe :string]] JsonValue
   [:fn nil-input-nil-output-check]])

(def EdnValue
  [:or :nil :boolean number? :string :keyword :symbol :map sequential? set?])

(def ProcessResult
  [:map
   [:exit :int]
   [:out :string]
   [:err [:maybe :string]]])

(defn absolute-path-return-check
  "The path helpers always return an absolute path string."
  [[_ ret]]
  (.isAbsolute (java.io.File. ret)))

(def abs-path
  [:=> [:cat :string] :string
   [:fn absolute-path-return-check]])

(defn tilde-fn-check
  "Render the same home prefix that `tilde` replaces."
  [[args ret]]
  (let [s (first args)
        home (or (System/getenv "AGENTCTL_HOME")
                 (System/getenv "HOME")
                 (System/getProperty "user.home"))
        expected (if (.startsWith s home)
                   (str "~" (subs s (count home)))
                   s)]
    (= ret expected)))

(def tilde
  [:=> [:cat :string] :string
   [:fn tilde-fn-check]])
(def real-path
  [:=> [:cat :string] :string
   [:fn absolute-path-return-check]])
(def exists?
  [:=> [:cat [:maybe :string]] [:maybe :boolean]
   [:fn expand-fn-check]])

(defn sha256-return-check
  "The returned digest is the SHA-256 of the supplied UTF-8 string."
  [[args ret]]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes (first args) "UTF-8"))
        expected (apply str (map #(format "%02x" %) bytes))]
    (= expected ret)))

(def sha256
  [:=> [:cat :string] [:re #"^[0-9a-f]{64}$"]
   [:fn sha256-return-check]])
(def slurp-safe
  [:=> [:cat [:maybe :string]] [:maybe :string]
   [:fn nil-input-nil-output-check]])
(def read-yaml
  [:=> [:cat [:maybe :string]] EdnValue
   [:fn nil-input-nil-output-check]])
(def read-edn
  [:=> [:cat [:maybe :string]] EdnValue
   [:fn nil-input-nil-output-check]])
(def write-json! [:=> [:cat :string EdnValue] :nil])
(def write-yaml! [:=> [:cat :string EdnValue] :nil])
(def write-text! [:=> [:cat :string :string] :nil])
(def timestamp [:=> [:cat] [:re #"^[0-9]{8}-[0-9]{6}$"]])
(def backup-dir [:=> [:cat] :string])
(def backup! [:=> [:cat :string] [:maybe :string]])
(def ShArg [:or :nil :string sequential?])
(def sh [:=> [:cat [:* ShArg]] ProcessResult])
(def sh-in [:=> [:cat :string [:* ShArg]] ProcessResult])
(def sh-env-in [:=> [:cat [:maybe :string] :map [:* ShArg]] ProcessResult])
(def sh-env [:=> [:cat :map [:* ShArg]] ProcessResult])

(defn which-fn-check
  "A blank command cannot resolve to an executable."
  [[args ret]]
  (or (re-find #"\S" (first args)) (nil? ret)))

(def which
  [:=> [:cat :string] [:maybe :string]
   [:fn which-fn-check]])

(defn deep-merge-fn-check
  "For non-map inputs, the second non-nil value wins."
  [[args ret]]
  (let [[a b] args]
    (or (and (map? a) (map? b))
        (= ret (if (some? b) b a)))))

(def deep-merge
  [:=> [:cat EdnValue EdnValue] EdnValue
   [:fn deep-merge-fn-check]])

(defn dissoc-in-fn-check
  "A non-empty path removes its final key when the parent was a map."
  [[args ret]]
  (let [[m path] args]
    (and (seq path)
         (let [parent-path (butlast path)
               parent-before (get-in m parent-path)
               parent-after (get-in ret parent-path)]
           (or (not (map? parent-before))
               (and (map? parent-after)
                    (not (contains? parent-after (last path)))))))))

(def NonEmptyPath
  [:and sequential? [:fn seq]])

(def dissoc-in
  [:=> [:cat :map NonEmptyPath] :map
   [:fn dissoc-in-fn-check]])

(defn prune-nils-fn-check
  [[args ret]]
  (= ret (into {} (remove (fn [[_ v]] (nil? v)) (first args)))))

(def prune-nils
  [:=> [:cat :map] :map
   [:fn prune-nils-fn-check]])

(defn kw->str-fn-check
  [[args ret]]
  (let [k (first args)]
    (= ret (if (keyword? k) (name k) (str k)))))

(def kw->str
  [:=> [:cat EdnValue] :string
   [:fn kw->str-fn-check]])

(defn id->str-fn-check
  [[args ret]]
  (let [k (first args)]
    (= ret (if (keyword? k) (subs (str k) 1) (str k)))))

(def id->str
  [:=> [:cat EdnValue] :string
   [:fn id->str-fn-check]])

(def key-str
  [:=> [:cat EdnValue] :string
   [:fn id->str-fn-check]])

(defn normalized-value?
  [v]
  (cond
    (keyword? v) false
    (map? v) (every? (fn [[k x]] (and (string? k) (normalized-value? x))) v)
    (sequential? v) (and (vector? v) (every? normalized-value? v))
    :else true))

(defn norm-fn-check
  [[args ret]]
  (let [v (first args)]
    (if (keyword? v)
      (= ret (name v))
      (normalized-value? ret))))

(def norm
  [:=> [:cat EdnValue] EdnValue
   [:fn norm-fn-check]])

(defn split-cmd-fn-check
  [[args ret]]
  (if (seq (first args)) (vector? ret) (nil? ret)))

(def split-cmd
  [:=> [:cat [:maybe :string]] [:maybe [:vector :string]]
   [:fn split-cmd-fn-check]])
