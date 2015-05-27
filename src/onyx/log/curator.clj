(ns onyx.log.curator
  (:require [clojure.core.async :refer [chan >!! <!! close! thread]]
            [taoensso.timbre :refer [fatal warn trace]]
            [onyx.static.default-vals :refer [defaults]])
  (:import [org.apache.zookeeper CreateMode]
           [org.apache.zookeeper KeeperException$NoNodeException KeeperException$NodeExistsException Watcher]
           [org.apache.curator.test TestingServer]
           [org.apache.zookeeper.data Stat]
           [org.apache.curator.framework CuratorFrameworkFactory CuratorFramework]
           [org.apache.curator.framework.api CuratorWatcher PathAndBytesable Versionable GetDataBuilder 
            SetDataBuilder DeleteBuilder ExistsBuilder GetChildrenBuilder Pathable Watchable]
           [org.apache.curator.framework.state ConnectionStateListener ConnectionState]
           [org.apache.curator.framework.imps CuratorFrameworkState]
           [org.apache.curator RetryPolicy]
           [org.apache.curator.retry BoundedExponentialBackoffRetry]))

;; Thanks to zookeeper-clj
(defn stat-to-map
  ([^org.apache.zookeeper.data.Stat stat]
   ;;(long czxid, long mzxid, long ctime, long mtime, int version, int cversion, int aversion, long ephemeralOwner, int dataLength, int numChildren, long pzxid)
   (when stat
     {:czxid (.getCzxid stat)
      :mzxid (.getMzxid stat)
      :ctime (.getCtime stat)
      :mtime (.getMtime stat)
      :version (.getVersion stat)
      :cversion (.getCversion stat)
      :aversion (.getAversion stat)
      :ephemeralOwner (.getEphemeralOwner stat)
      :dataLength (.getDataLength stat)
      :numChildren (.getNumChildren stat)
      :pzxid (.getPzxid stat)})))

(defn event-to-map
  ([^org.apache.zookeeper.WatchedEvent event]
     (when event
       {:event-type (keyword (.name (.getType event)))
        :keeper-state (keyword (.name (.getState event)))
        :path (.getPath event)})))

;; Watcher

(defn make-watcher
  ([handler]
     (reify Watcher
       (process [this event]
         (handler (event-to-map event))))))

(defn ^CuratorFramework connect
  ([connection-string]
   (connect connection-string ""))
  ([connection-string ns]
   (connect connection-string ns 
            (BoundedExponentialBackoffRetry. 
              (:onyx.zookeeper/backoff-base-sleep-time-ms defaults) 
              (:onyx.zookeeper/backoff-max-sleep-time-ms defaults)
              (:onyx.zookeeper/backoff-max-retries defaults))))
  ([connection-string ns ^RetryPolicy retry-policy]
   (doto
     (.. (CuratorFrameworkFactory/builder)
         (namespace ns)
         (connectString connection-string)
         (retryPolicy retry-policy)
         (build))
     .start)))

(defn close
  "Closes the connection to the ZooKeeper server."
  [^CuratorFramework client] 
  (.close client))

(defn create-mode [opts]
  (cond (and (:persistent? opts) (:sequential? opts))
        CreateMode/PERSISTENT_SEQUENTIAL
        (:persistent? opts)
        CreateMode/PERSISTENT
        (:sequential? opts)
        CreateMode/EPHEMERAL_SEQUENTIAL
        :else
        CreateMode/EPHEMERAL))

(defn create
  [^CuratorFramework client path & {:keys [data] :as opts}]
  (try 
    (let [cr ^SetDataBuilder (.. client 
                 create 
                 (withMode (create-mode opts)))]
      (if data 
        (.forPath ^SetDataBuilder cr path data)
        (.forPath ^SetDataBuilder cr path)))
    (catch org.apache.zookeeper.KeeperException$NodeExistsException e
      false)))

(defn create-all
  [^CuratorFramework client path & {:keys [data] :as opts}]
  (try 
    (let [cr (.. client 
                 create 
                 creatingParentsIfNeeded 
                 (withMode (create-mode opts)))]
      (if data 
        (.forPath ^SetDataBuilder cr path data)
        (.forPath ^SetDataBuilder cr path)))
    (catch org.apache.zookeeper.KeeperException$NodeExistsException e
      false) ))

(defn delete
  "Deletes the given node if it exists. Otherwise returns false."
  [^CuratorFramework client path]
  (try
    (.forPath ^DeleteBuilder (.delete client) path )
    (catch KeeperException$NoNodeException e false)))

(defn children 
  ([^CuratorFramework client path & {:keys [watcher]}]
   (let [children-builder ^GetChildrenBuilder (.getChildren client)]
     (if watcher 
       (.forPath ^GetChildrenBuilder (.usingWatcher children-builder ^Watcher (make-watcher watcher)) path)
       (.forPath ^GetChildrenBuilder children-builder path)))))

(defn data [^CuratorFramework client path]
  (let [stat ^Stat (Stat.)
        data (.forPath ^GetDataBuilder (.storingStatIn ^GetDataBuilder (.getData client) stat) path)] 
    {:data data
     :stat (stat-to-map stat)}))

(defn set-data [^CuratorFramework client path data version]
  (.forPath ^SetDataBuilder (.withVersion ^SetDataBuilder (.setData client) 
                                          version) 
            path 
            data))

(defn exists [^CuratorFramework client path & {:keys [watcher]}]
  (stat-to-map 
    (let [builder ^ExistsBuilder (.. client checkExists)]
      (if watcher
        (.forPath ^ExistsBuilder (.usingWatcher builder ^Watcher (make-watcher watcher)) path)
        (.forPath builder path))))) 
