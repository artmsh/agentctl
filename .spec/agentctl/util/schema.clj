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
