(ns agentctl.scope.schema)

(def Entity
  [:map
   [:kind :keyword]
   [:id :keyword]
   [:in [:or [:enum :all :none] [:vector :string]]]
   [:targets [:vector :string]]
   [:order :int]
   [:decl :map]])

(def ToolsFor
  [:=> [:cat :keyword] [:vector :keyword]])

(def compile-config
  [:=> [:cat :map [:function ToolsFor]]
   [:map
    [:raw :map]
    [:entities [:vector Entity]]
    [:active-tools [:set :keyword]]]])
