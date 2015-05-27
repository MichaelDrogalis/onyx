(ns onyx.scheduling.common-task-scheduler
  (:require [clojure.core.async :refer [chan go >! <! close! >!!]]
            [clojure.set :refer [union difference map-invert]]
            [clojure.data :refer [diff]]
            [com.stuartsierra.component :as component]
            [onyx.log.commands.common :as common]
            [onyx.extensions :as extensions]
            [taoensso.timbre]))

(defn active-tasks-only
  "Filters out tasks that are currently being sealed."
  [replica tasks]
  (filter #(nil? (get-in replica [:sealing-task %])) tasks))

(defn incomplete-tasks [replica job tasks]
  (let [tasks (get-in replica [:tasks job])
        completed (get-in replica [:completions job])]
    (filter identity (second (diff completed tasks)))))

(defn preallocated-grouped-task? [replica job task]
  (and (not (nil? (get-in replica [:flux-policies job task])))
       (> (count (get-in replica [:allocations job task])) 0)))

(defn filter-grouped-tasks [replica job allocations]
  (into
   {}
   (remove
    (fn [[k v]]
      (not (nil? (get-in replica [:flux-policies job k]))))
    allocations)))

(defmulti drop-peers
  (fn [replica job n]
    (get-in replica [:task-schedulers job])))

(defmethod drop-peers :default
  [replica job n]
  (let [scheduler (get-in replica [:task-schedulers job])]
    (throw (ex-info 
             (format "Task scheduler %s not recognized. Check that you have not supplied a job scheduler instead." 
                     scheduler)
             {:replica replica}))))

(defmulti task-distribute-peer-count
  (fn [replica job n]
    (get-in replica [:task-schedulers job])))

(defmethod task-distribute-peer-count :default
  [replica job n]
  (throw (ex-info (format "Task scheduler %s not recognized" (get-in replica [:task-schedulers job]))
                  {:task-scheduler (get-in replica [:task-schedulers job])
                   :job job})))
