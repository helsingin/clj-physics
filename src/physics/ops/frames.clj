(ns physics.ops.frames
  "Operational frame normalization.
   Automatically converts states to a target frame (default :ecef)."
  (:require [physics.frames :as frames]
            [physics.ops.kinematics :as k]))

(defn- get-geodetic-vec [pos]
  (if (vector? pos)
    pos
    [(:lat-deg pos) (:lon-deg pos) (:alt-m pos)]))

(defn- normalize-to-ecef
  [state]
  (let [frame (:frame state :world)
        pos (:position state)]
    (case frame
      :ecef state
      :world state
      :wgs84 (let [[lat lon alt] (get-geodetic-vec pos)]
               (assoc state 
                      :position (frames/geodetic->ecef 
                                 {:lat-deg lat :lon-deg lon :alt-m alt})
                      :frame :ecef))
      (throw (ex-info "Cannot auto-normalize frame without context" {:frame frame})))))

(defn ensure-frame
  "Convert state to target frame. Currently only supports :ecef normalization from :wgs84."
  [state target-frame]
  (if (= target-frame :ecef)
    (normalize-to-ecef state)
    (throw (ex-info "Target frame not supported" {:target target-frame}))))
