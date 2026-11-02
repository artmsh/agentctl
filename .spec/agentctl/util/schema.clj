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

(def path-sha256
  [:=> [:cat [:maybe :string]]
   [:maybe [:re #"^[0-9a-f]{64}$"]]])

(def JsonValue
  [:schema
   {:registry {::json-value
               [:or :nil :boolean number? :string
                [:sequential [:ref ::json-value]]
                [:map-of :keyword [:ref ::json-value]]]}}
   [:ref ::json-value]])

(def read-json
  [:=> [:cat [:maybe :string]] JsonValue])

(def EdnValue
  [:or :nil :boolean number? :string :keyword :symbol :map sequential? set?])

(def ProcessResult
  [:map
   [:exit :int]
   [:out :string]
   [:err [:maybe :string]]])

(def abs-path [:=> [:cat :string] :string])
(def tilde [:=> [:cat :string] :string])
(def real-path [:=> [:cat :string] :string])
(def exists? [:=> [:cat [:maybe :string]] [:maybe :boolean]])
(def sha256 [:=> [:cat :string] [:re #"^[0-9a-f]{64}$"]])
(def slurp-safe [:=> [:cat [:maybe :string]] [:maybe :string]])
(def read-yaml [:=> [:cat [:maybe :string]] EdnValue])
(def read-edn [:=> [:cat [:maybe :string]] EdnValue])
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
(def which [:=> [:cat :string] [:maybe :string]])
(def deep-merge [:=> [:cat EdnValue EdnValue] EdnValue])
(def dissoc-in [:=> [:cat :map sequential?] :map])
(def prune-nils [:=> [:cat :map] :map])
(def kw->str [:=> [:cat EdnValue] :string])
(def id->str [:=> [:cat EdnValue] :string])
(def key-str [:=> [:cat EdnValue] :string])
(def norm [:=> [:cat EdnValue] EdnValue])
(def split-cmd [:=> [:cat [:maybe :string]] [:maybe [:vector :string]]])
