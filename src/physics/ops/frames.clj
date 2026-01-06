(ns physics.ops.frames
  "Operational frame normalization.
   Automatically converts states to a target frame (default :ecef)."
  (:require [physics.frames :as frames]
            [physics.ops.kinematics :as k]))

(defn- normalize-to-ecef
  [state]
  (let [frame (:frame state :world) ;; Default to :world -> ECEF
        pos (:position state)]
    (case frame
      :ecef state
      :world state ;; Assume world=ecef for now
      :wgs84 (assoc state 
                    :position (frames/geodetic->ecef 
                               {:lat-deg (nth pos 0) :lon-deg (nth pos 1) :alt-m (nth pos 2)})
                    :frame :ecef)
      ;; ENU requires an origin, which is state-dependent context. 
      ;; We cannot auto-convert ENU without context.
      (throw (ex-info "Cannot auto-normalize frame without context" {:frame frame})))))

(defn ensure-frame
  "Convert state to target frame. Currently only supports :ecef normalization from :wgs84."
  [state target-frame]
  (if (= target-frame :ecef)
    (normalize-to-ecef state)
    (throw (ex-info "Target frame not supported" {:target target-frame}))))
