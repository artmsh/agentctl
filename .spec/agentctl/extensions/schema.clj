(ns agentctl.extensions.schema)

(def Extension
  [:map
   [:id :keyword]
   [:root :string]
   [:for-tools [:set :keyword]]
   [:uri :string]
   [:type :keyword]
   [:ref {:optional true} [:maybe :string]]
   [:build {:optional true} [:maybe :string]]])

(def BuildExtension
  [:and Extension [:map [:build :string]]])

(def GitRevision
  [:and :string [:fn #(>= (count %) 7)]])

(def Op
  [:map
   [:action :keyword]
   [:tool :keyword]
   [:kind :keyword]
   [:id :keyword]
   [:target {:optional true} :string]
   [:summary {:optional true} :string]
   [:risk {:optional true} :keyword]
   [:warn {:optional true} :boolean]
   [:owns {:optional true} [:vector [:tuple :keyword :keyword :keyword]]]
   [:cmds {:optional true} [:vector [:vector :string]]]
   [:runs {:optional true} [:vector :map]]
   [:diffs {:optional true} [:vector :map]]
   [:exec! {:optional true} ifn?]])

(def stamp-file
  [:=> [:cat :keyword] :string])

(def stamp
  [:=> [:cat [:maybe :string] [:maybe :string]] :string])

(def built?
  [:=> [:cat Extension] :boolean])

(def owns
  [:=> [:cat Extension] [:vector [:tuple :keyword :keyword :keyword]]])

(def exec
  [:=> [:cat :string [:maybe :string] [:vector :string]] :nil])

(def fetch-op
  [:=> [:cat Extension :boolean [:maybe GitRevision] [:maybe GitRevision]] Op])

(def build-op
  [:=> [:cat BuildExtension] Op])

(def source-ops
  [:=> [:cat [:map [:extensions [:map-of :keyword Extension]]] [:set :keyword]]
   [:sequential Op]])

(def ready?
  [:=> [:cat Extension] :boolean])
