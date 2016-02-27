(ns onyx.log.full-test
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [com.stuartsierra.component :as component]
            [onyx.log.entry :refer [create-log-entry]]
            [onyx.extensions :as extensions]
            [onyx.api :as api]
            [onyx.test-helper :refer [with-test-env load-config]]
            [schema.test]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [onyx.log.curator :as zk]))

(use-fixtures :once schema.test/validate-schemas)

(deftest ^:smoke log-full-test
  (testing "peers all join and watch each other"
    (let [config (load-config)
          onyx-id (java.util.UUID/randomUUID)
          env-config (assoc (:env-config config) :onyx/tenancy-id onyx-id)
          peer-config (assoc (:peer-config config) :onyx/tenancy-id onyx-id)
          n-peers 20]

      (with-test-env [test-env [n-peers env-config peer-config]]
        (let [ch (chan n-peers)
              replica (loop [replica (extensions/subscribe-to-log (:log (:env test-env)) ch)]
                        (let [entry (<!! ch)
                              new-replica (extensions/apply-log-entry entry replica)]
                          (if (< (count (:pairs new-replica)) n-peers)
                            (recur new-replica)
                            new-replica)))]
          (is (= {} (:prepared replica)))
          (is (= {} (:accepted replica)))
          (is (= (set (keys (:pairs replica)))
                 (set (vals (:pairs replica)))))
          (is (= n-peers (count (:peers replica)))))))))
