(ns agentctl.state
  "Manifest of resources agentctl owns.

   Without it `apply` can only ever converge upward: a resource deleted from
   agents.edn would be invisible. Anything NOT recorded here is treated as
   hand-made and is never deleted."
  (:require [agentctl.util :as u]
            [clojure.pprint :as pp]))

(def path (str u/home "/.config/agentctl/state.edn"))

(def empty-state {:version 1 :managed {}})

(defn load-state []
  (or (u/read-edn path) empty-state))

(defn save! [state]
  (u/write-text! path (with-out-str (pp/pprint (assoc state :version 1)))))

(defn key-for [tool kind id] [tool kind (u/id->str id)])

(defn managed? [state tool kind id]
  (contains? (:managed state) (key-for tool kind id)))

(defn managed-ids
  "All ids agentctl owns for a (tool, kind) pair."
  [state tool kind]
  (->> (:managed state)
       (keys)
       (filter (fn [[t k _]] (and (= t tool) (= k kind))))
       (map last)
       (set)))

(defn record [state tool kind id data]
  (assoc-in state [:managed (key-for tool kind id)]
            (merge {:at (u/timestamp)} data)))

(defn forget [state tool kind id]
  (update state :managed dissoc (key-for tool kind id)))

(defn entry [state tool kind id]
  (get-in state [:managed (key-for tool kind id)]))
