(ns onyx.log.backpressure-generative-test
  (:require [onyx.messaging.dummy-messenger :refer [dummy-messenger]]
            [onyx.log.generators :as log-gen]
            [onyx.extensions :as extensions]
            [onyx.api :as api]
            [onyx.static.planning :as planning]
            [onyx.test-helper :refer [job-allocation-counts]]
            [clojure.set :refer [intersection]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test :refer :all]
            [com.gfredericks.test.chuck :refer [times]]
            [com.gfredericks.test.chuck.clojure-test :refer [checking]]))

(def onyx-id (java.util.UUID/randomUUID))

(def peer-config
  {:onyx/tenancy-id onyx-id
   :onyx.messaging/impl :dummy-messenger})

(def messenger (dummy-messenger {}))

(def job-1-id #uuid "f55c14f0-a847-42eb-81bb-0c0390a88608")

(def job-1
  {:workflow [[:a :b] [:b :c]]
   :catalog [{:onyx/name :a
              :onyx/plugin :onyx.plugin.core-async/input
              :onyx/type :input
              :onyx/medium :core.async
              :onyx/batch-size 20
              :onyx/doc "Reads segments from a core.async channel"}

             {:onyx/name :b
              :onyx/fn :mock/fn
              :onyx/type :function
              :onyx/batch-size 20}

             {:onyx/name :c
              :onyx/plugin :onyx.plugin.core-async/output
              :onyx/type :output
              :onyx/medium :core.async
              :onyx/batch-size 20
              :onyx/doc "Writes segments to a core.async channel"}]
   :task-scheduler :onyx.task-scheduler/balanced})

(def job-2-id #uuid "5813d2ec-c486-4428-833d-e8373910ae14")

(def job-2
  {:workflow [[:d :e] [:e :f]]
   :catalog [{:onyx/name :d
              :onyx/plugin :onyx.plugin.core-async/input
              :onyx/type :input
              :onyx/medium :core.async
              :onyx/batch-size 20
              :onyx/doc "Reads segments from a core.async channel"}

             {:onyx/name :e
              :onyx/fn :mock/fn
              :onyx/type :function
              :onyx/batch-size 20}

             {:onyx/name :f
              :onyx/plugin :onyx.plugin.core-async/output
              :onyx/type :output
              :onyx/medium :core.async
              :onyx/batch-size 20
              :onyx/doc "Writes segments to a core.async channel"}]
   :task-scheduler :onyx.task-scheduler/balanced})

(def job-3-id #uuid "58d199e8-4ea4-4afd-a112-945e97235924")

(def job-3
  {:workflow [[:g :h] [:h :i]]
   :catalog [{:onyx/name :g
              :onyx/plugin :onyx.plugin.core-async/input
              :onyx/type :input
              :onyx/medium :core.async
              :onyx/batch-size 20
              :onyx/doc "Reads segments from a core.async channel"}

             {:onyx/name :h
              :onyx/fn :mock/fn
              :onyx/type :function
              :onyx/batch-size 20}

             {:onyx/name :i
              :onyx/plugin :onyx.plugin.core-async/output
              :onyx/type :output
              :onyx/medium :core.async
              :onyx/batch-size 20
              :onyx/doc "Writes segments to a core.async channel"}]
   :task-scheduler :onyx.task-scheduler/balanced})

(deftest simple-backpressure
  (checking
    "Checking backpressure handled correctly"
    (times 50)
    [{:keys [entries replica log peer-choices]}
     (log-gen/apply-entries-gen
       (gen/return
         {:replica {:job-scheduler :onyx.job-scheduler/balanced
                    :messaging {:onyx.messaging/impl :dummy-messenger}}
          :message-id 0
          :entries (-> (log-gen/generate-join-queues (log-gen/generate-group-and-peer-ids 1 12))
                       (assoc :job-1 {:queue [(api/create-submit-job-entry job-1-id
                                                                   peer-config
                                                                   job-1
                                                                   (planning/discover-tasks (:catalog job-1) (:workflow job-1)))]})
                       (assoc :bp1 {:queue [{:fn :backpressure-on :args {:peer :g1-p1}}
                                            {:fn :backpressure-off :args {:peer :g1-p1}}]})
                       (assoc :bp2 {:predicate (fn [replica entry]
                                                 (= :active (get-in replica [:peer-state :g1-p2])))
                                    :queue [{:fn :backpressure-on :args {:peer :g1-p2}}]})
                       (assoc :bp-on-peer-missing {:queue [{:fn :backpressure-on :args {:peer :g1-p13}}]}))
          :log []
          :peer-choices []}))]
    ;(spit "log.edn" (pr-str log))
    (is (empty? (apply concat (vals entries))))
    (is (= :active (get (:peer-state replica) :g1-p1)))
    (is (= :backpressure (get (:peer-state replica) :g1-p2)))
    (is (nil? (get (:peer-state replica) :g1-p13)))))

(deftest backpressure-kill-job
  (checking
    "Checking balanced allocation causes peers to be evenly split"
    (times 50)
    [{:keys [replica log peer-choices]}
     (log-gen/apply-entries-gen
       (gen/return
         {:replica {:job-scheduler :onyx.job-scheduler/balanced
                    :messaging {:onyx.messaging/impl :dummy-messenger}}
          :message-id 0
          :entries (-> (log-gen/generate-join-queues (log-gen/generate-group-and-peer-ids 1 12))
                       (assoc :job-1 {:queue [(api/create-submit-job-entry
                                                job-1-id
                                                peer-config
                                                job-1
                                                (planning/discover-tasks (:catalog job-1) (:workflow job-1)))]}
                              :job-2 {:queue [(api/create-submit-job-entry
                                                job-2-id
                                                peer-config
                                                job-2
                                                (planning/discover-tasks (:catalog job-2) (:workflow job-2)))]}
                              :job-3 {:predicate (fn [replica entry]
                                                   (some #{:p2} (:peers replica)))
                                      :queue [(api/create-submit-job-entry
                                                job-3-id
                                                peer-config
                                                job-3
                                                (planning/discover-tasks (:catalog job-3) (:workflow job-3)))]})

                       (assoc :peer-backpressure-then-kill {:predicate (fn [replica entry]
                                                                         (some #{:g1-p3} (:peers replica)))
                                                            :queue [{:fn :backpressure-on :args {:peer :g1-p3}}
                                                                    {:fn :kill-job :args {:job job-3-id}}]})
                       (assoc :bp1 {:queue [{:fn :backpressure-on :args {:peer :g1-p1}}
                                            {:fn :backpressure-off :args {:peer :g1-p1}}]})
                       (assoc :bp2 {:predicate (fn [replica entry]
                                                 (some #{:g1-p2} (:peers replica)))
                                    :queue [{:fn :backpressure-on :args {:peer :g1-p2}}]}))
          :log []
          :peer-choices []}))]
    (is (= :active (get (:peer-state replica) :g1-p1)))
    (is (#{:backpressure :active} (get (:peer-state replica) :g1-p2)))
    (is (not= :idle (get (:peer-state replica) :g1-p3)))))

(deftest backpressure-off-already-left
  (checking
    "Checking backpressure off handled when peer has already left"
    (times 50)
    [{:keys [replica log peer-choices]}
     (log-gen/apply-entries-gen
       (gen/return
         {:replica {:job-scheduler :onyx.job-scheduler/balanced
                    :messaging {:onyx.messaging/impl :dummy-messenger}}
          :message-id 0
          :entries (-> (log-gen/generate-join-queues (log-gen/generate-group-and-peer-ids 1 6))
                       (assoc :job-1 {:queue [(api/create-submit-job-entry
                                                job-1-id
                                                peer-config
                                                job-1
                                                (planning/discover-tasks (:catalog job-1) (:workflow job-1)))]})
                       (assoc :bp1 {:predicate (fn [replica entry]
                                                 (or (some #{:g1-p1} (:peers replica))
                                                     (= :backpressure-off (:fn entry))))
                                    :queue [{:fn :backpressure-on :args {:peer :g1-p1}}
                                            {:fn :leave-cluster :args {:id :g1-p1}}
                                            {:fn :backpressure-off :args {:peer :g1-p1}}]}))
          :log []
          :peer-choices []}))]
    (is (= {:g1-p3 :active :g1-p4 :active :g1-p5 :active
            :g1-p6 :active :g1-p2 :active}
           (:peer-state replica)))))

(deftest backpressure-on-already-left
  (checking
    "Checking balanced allocation causes peers to be evenly split"
    (times 50)
    [{:keys [replica log peer-choices]}
     (log-gen/apply-entries-gen
       (gen/return
         {:replica {:job-scheduler :onyx.job-scheduler/balanced
                    :messaging {:onyx.messaging/impl :dummy-messenger}}
          :message-id 0
          :entries (-> (log-gen/generate-join-queues (log-gen/generate-group-and-peer-ids 1 3))
                       (assoc :job-1 {:queue [(api/create-submit-job-entry
                                                job-1-id
                                                peer-config
                                                job-1
                                                (planning/discover-tasks (:catalog job-1) (:workflow job-1)))]})
                       (assoc :bp2 {:predicate (fn [replica entry]
                                                 (or (some #{:g1-p2} (:peers replica))
                                                     (= :backpressure-on (:fn entry))))
                                    :queue [{:fn :leave-cluster :args {:id :g1-p2}}
                                            {:fn :backpressure-on :args {:peer :g1-p2}}]}))
          :log []
          :peer-choices []}))]
    (is (nil? (get-in replica [:peer-state :g1-p2])))))
