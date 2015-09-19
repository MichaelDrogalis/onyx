(ns onyx.triggers.triggers-api
  (:require [onyx.static.planning :refer [find-window]]
            [onyx.windowing.units :refer [to-standard-units coerce-key]]
            [onyx.windowing.window-id :as wid]
            [onyx.static.default-vals :as d]
            [taoensso.timbre :refer [info warn fatal]]))

(defmulti trigger-setup
  "Sets up any vars or state to subsequently
   use in trigger invocations. Must return an
   updated event map."
  (fn [event trigger]
    (:trigger/on trigger)))

(defmulti trigger-notifications
  "Returns a set of keywords denoting notifications that
   this trigger responds to. Currently only supports `:new-segment`."
  (fn [event trigger]
    (:trigger/on trigger)))

(defmulti trigger-fire?
  "Returns true if this trigger should fire, therefore refinining the
   state of each extent in this window and invoking the trigger sync function.
   This function is invoked exactly once per window, so this function may
   perform side-effects such as mainining counters."
  (fn [event trigger & args]
    (:trigger/on trigger)))

(defmulti trigger-teardown
  "Tears down any vars or state created to support this trigger.
   Must return an updated event map."
  (fn [event trigger]
    (:trigger/on trigger)))

(defmulti refine-state
  "Updates the local window state according to the refinement policy.
   Must return the new local window state in its entirety."
  (fn [event trigger]
    (:trigger/refinement trigger)))

;; Adapted from Prismatic Plumbing:
;; https://github.com/Prismatic/plumbing/blob/c53ba5d0adf92ec1e25c9ab3b545434f47bc4156/src/plumbing/core.cljx#L346-L361
(defn swap-pair!
  "Like swap! but returns a pair [old-val new-val]"
  ([a f]
     (loop []
       (let [old-val @a
             new-val (f old-val)]
         (if (compare-and-set! a old-val new-val)
           [old-val new-val]
           (recur)))))
  ([a f & args]
     (swap-pair! a #(apply f % args))))

(defmethod refine-state :accumulating
  [{:keys [onyx.core/window-state]} trigger]
  @window-state)

(defmethod refine-state :discarding
  [{:keys [onyx.core/window-state]} trigger]
  (first (swap-pair! window-state #(dissoc % (:trigger/window-id trigger)))))

(defmethod trigger-setup :default
  [event trigger]
  event)

(defmethod trigger-teardown :default
  [event trigger]
  event)

(defn iterate-windows [event trigger window-ids f opts]
  (doseq [[window-id state] window-ids]
    (let [window (find-window (:onyx.core/windows event) (:trigger/window-id trigger))
          win-min (or (:window/min-value window) (get d/defaults :onyx.windowing/min-value))
          w-range (apply to-standard-units (:window/range window))
          w-slide (apply to-standard-units (or (:window/slide window) (:window/range window)))
          lower (wid/extent-lower win-min w-range w-slide window-id)
          upper (wid/extent-upper win-min w-slide window-id)
          args (merge opts
                      {:window window :window-id window-id :w-range w-range
                       :lower-extent lower :upper-extent upper})]
      (when (f event trigger args)
        (refine-state event trigger)
        ((:trigger/sync-fn trigger) event window-id lower upper state)))))

(defn fire-trigger! [event window-state-ref trigger opts]
  (when (some #{(:context opts)} (trigger-notifications event trigger))
    (let [window-ids (get @window-state-ref (:trigger/window-id trigger))]
      (if (:trigger/fire-all-extents? trigger)
        (when (trigger-fire? event trigger opts)
          (iterate-windows event trigger window-ids (constantly true) opts))
        (iterate-windows event trigger window-ids trigger-fire? opts)))))
