(ns onyx.log.commands.add-virtual-peer
  (:require [onyx.extensions :as extensions]
            [onyx.schema :refer [Replica LogEntry Reactions ReplicaDiff State]]
            [onyx.scheduling.common-job-scheduler :refer [reconfigure-cluster-workload]]
            [onyx.log.commands.common :as common]
            [taoensso.timbre :as timbre :refer [info]]
            [schema.core :as s]))

(defn add-site-acker [replica {:keys [id peer-site]}]
  (assert (:messaging replica) ":messaging key missing in replica, cannot continue")
  (-> replica
      (assoc-in [:peer-sites id]
                (merge
                  peer-site
                  (extensions/assign-acker-resources replica
                                                     id
                                                     peer-site
                                                     (:peer-sites replica))))))

(defn register-peer-info [replica args]
  (-> replica
      (update-in [:groups-index (:group-id args)] (fnil conj #{}) (:id args))
      (assoc-in [:groups-reverse-index (:id args)] (:group-id args))
      (assoc-in [:peer-state (:id args)] :idle)
      (assoc-in [:peer-tags (:id args)] (:tags args))
      (add-site-acker args)))

(s/defmethod extensions/apply-log-entry :add-virtual-peer :- Replica
  [{:keys [args]} :- LogEntry replica :- Replica]
  (cond (get-in replica [:left (:group-id args)])
        replica
        (some #{(:group-id args)} (:groups replica))
        (-> replica
            (update-in [:peers] conj (:id args))
            (update-in [:peers] vec)
            (register-peer-info args)
            (reconfigure-cluster-workload))
        :else 
        (-> replica
            (update-in [:orphaned-peers (:group-id args)] (fnil conj []) (:id args))
            (register-peer-info args))))

(s/defmethod extensions/replica-diff :add-virtual-peer :- ReplicaDiff
  [{:keys [args]} old new]
  (if-not (= old new) 
    {:virtual-peer-id (:id args)}))

(s/defmethod extensions/fire-side-effects! [:add-virtual-peer :peer] :- State
  [{:keys [args message-id] :as entry} old new diff state]
  (when (= (:id args) (:id state))
    (let [peer-site (get-in new [:peer-sites (:id state)])]
      (extensions/register-acker (:messenger state) peer-site)))
  (common/start-new-lifecycle old new diff state :peer-reallocated))
