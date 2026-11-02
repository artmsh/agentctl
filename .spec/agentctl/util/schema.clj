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
