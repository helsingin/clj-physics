(ns physics.spatial.pose
  "Pose (position + orientation) primitives."
  (:require [physics.spatial.frame :as frame]))

(defn ->pose
  "Construct a pose map. Keys:
   :position   vector [x y z]
   :orientation quaternion [w x y z]
   Optional: :velocity, :acceleration, :timestamp, :frame"
  [{:keys [position orientation velocity acceleration timestamp frame]
    :or {position [0.0 0.0 0.0]
         orientation frame/identity-quaternion}}]
  {:position (vec position)
   :orientation (frame/normalize-quaternion orientation)
   :velocity (some-> velocity vec)
   :acceleration (some-> acceleration vec)
   :timestamp timestamp
   :frame frame})

(defn interpolate
  "Interpolate between pose a and b by t in [0,1]."
  [pose-a pose-b t]
  (letfn [(lerp [v1 v2]
            (mapv #(+ %1 (* t (- %2 %1))) v1 v2))]
    (->pose {:position (lerp (:position pose-a) (:position pose-b))
             :orientation (frame/slerp (:orientation pose-a)
                                       (:orientation pose-b)
                                       t)
             :velocity (when (and (:velocity pose-a) (:velocity pose-b))
                         (lerp (:velocity pose-a) (:velocity pose-b)))
             :frame (or (:frame pose-b) (:frame pose-a))})))
