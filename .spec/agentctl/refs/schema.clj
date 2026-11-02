(ns agentctl.refs.schema)

(def EdnValue
  [:or :nil :boolean number? :string :keyword :symbol :map sequential? set?])

(def Ref
  [:map
   [:ref/kind [:enum :env :bw :file :cmd :literal]]])

(def Resolution
  [:map
   [:status [:enum :ok :missing :unknown :error]]
   [:value {:optional true} EdnValue]
   [:message {:optional true} :string]])

(def ref?
  [:=> [:cat EdnValue] :boolean])

(def dollar
  [:=> [:cat :string] Ref])

(def parse
  [:=> [:cat EdnValue] [:maybe Ref]])

(def secret-ref?
  [:=> [:cat EdnValue] :boolean])

(def describe
  [:=> [:cat EdnValue] :string])

(def bw-unlocked?
  [:=> [:cat] :boolean])

(def bw-fetch
  [:=> [:cat Ref] Resolution])

(def resolve-ref
  [:=> [:cat EdnValue] Resolution])

(def resolved-value
  [:=> [:cat EdnValue] EdnValue])

(def secret-shaped?
  [:=> [:cat [:maybe [:or :keyword :string]] EdnValue] :boolean])

(def credential?
  [:=> [:cat [:maybe [:or :keyword :string]] EdnValue] :boolean])

(def redact
  [:=> [:cat [:or :string :keyword] [:or :string :keyword]] :string])

(def mask
  [:=> [:cat EdnValue] :string])
