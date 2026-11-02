(ns agentctl.ownership.schema)

(def PlacementEntity
  [:map
   [:kind :keyword]
   [:id :keyword]
   [:in [:or [:enum :all :none] [:vector :string]]]
   [:targets [:vector :string]]
   [:decl :map]])

(def Placement
  [:map
   [:entity-id :keyword]
   [:tool :keyword]
   [:kind :keyword]
   [:resolved-path :string]])

(def placements
  [:=> [:cat [:map [:entities [:vector PlacementEntity]]]]
   [:map-of [:tuple :keyword :keyword :keyword :string] Placement]])
