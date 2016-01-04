(ns onyx.static.rotating-seq)

(defn create-r-seq [bucket-lifetime expire-interval]
  (assert (zero? (mod bucket-lifetime expire-interval)) "Bucket lifetime must divide evenly over expiration interval")
  (let [n-buckets (int (Math/ceil (/ bucket-lifetime expire-interval)))]
    (vec (repeat n-buckets (list)))))

(defn add-to-head [r-seq elements]
  (assoc r-seq 0 (into (r-seq 0) elements)))

(defn expire-bucket [r-seq]
  (vec (conj (butlast r-seq) (list))))
