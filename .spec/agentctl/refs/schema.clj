(ns agentctl.refs.schema
  (:require [clojure.string :as str]))

(def EdnValue
  [:or :nil :boolean number? :string :keyword :symbol :map sequential? set?])

(def NonBlankText
  [:and :string [:fn #(not (str/blank? %))]])

(def EnvRef
  [:map
   [:ref/kind [:= :env]]
   [:ref/var [:and NonBlankText [:fn #(not (re-find #"[=\u0000]" %))]]]])

(def BwRef
  [:map
   [:ref/kind [:= :bw]]
   [:ref/item NonBlankText]
   [:ref/field NonBlankText]
   [:ref/folder {:optional true} [:maybe NonBlankText]]])

(def Ref
  [:multi {:dispatch :ref/kind}
   [:env EnvRef]
   [:bw BwRef]
   [:file [:map [:ref/kind [:= :file]] [:ref/path NonBlankText]]]
   [:cmd [:map [:ref/kind [:= :cmd]] [:ref/cmd NonBlankText]]]
   [:literal [:map [:ref/kind [:= :literal]] [:ref/value EdnValue]]]])

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
  [:=> [:cat BwRef] Resolution])

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
