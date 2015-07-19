(ns onyx.log.commands.notify-join-cluster
  (:require [clojure.core.async :refer [chan go >! <! close!]]
            [clojure.set :refer [union difference map-invert]]
            [taoensso.timbre :refer [info] :as timbre]
            [clojure.data :refer [diff]]
            [onyx.extensions :as extensions]))

(defmethod extensions/apply-log-entry :notify-join-cluster
  [{:keys [args]} replica]
  (let [prepared (get (map-invert (:prepared replica)) (:observer args))]
    (-> replica
        (update-in [:accepted] merge {prepared (:observer args)})
        (update-in [:prepared] dissoc prepared))))

(defmethod extensions/replica-diff :notify-join-cluster
  [entry old new]
  (let [rets (second (diff (:accepted old) (:accepted new)))]
    (assert (<= (count rets) 1))
    (when (seq rets)
      {:observer (first (vals rets))
       :subject (or (get-in old [:pairs (first (keys rets))]) (first (keys rets)))
       :accepted-observer (first (keys rets))
       :accepted-joiner (first (vals rets))})))

(defmethod extensions/reactions :notify-join-cluster
  [entry old new diff peer-args]
  (cond (and (= (vals diff) (remove nil? (vals diff))) 
             (= (:id peer-args) (:observer diff)))
        [{:fn :accept-join-cluster
          :args diff
          :immediate? true}]  
        (= (:id peer-args) (:observer (:args entry)))
        [{:fn :abort-join-cluster
          :args {:id (:observer (:args entry))}
          :immediate? true}]))

(defmethod extensions/fire-side-effects! :notify-join-cluster
  [{:keys [args]} old new diff {:keys [monitoring] :as state}]
  (if (= (:id state) (:observer diff))
    (let [ch (chan 1)]
      (extensions/emit monitoring {:event :peer-notify-join :id (:id state)})
      (extensions/on-delete (:log state) (:subject diff) ch)
      (go (when (<! ch)
            (extensions/write-log-entry
             (:log state)
             {:fn :leave-cluster :args {:id (:subject diff)}}))
          (close! ch))
      (close! (or (:watch-ch state) (chan)))
      (assoc state :watch-ch ch))
    state))
