(ns physics.spatial.geometry
  "Core geometric primitives for mission planning."
  (:require [physics.spatial.frame :as frame]))

(defn- ensure-frame [frame]
  (or frame :world))

(defn point
  "Create a point geometry." 
  ([coords] (point coords {}))
  ([coords {:keys [frame]}]
   {:pre [(= 3 (count coords))]}
   {:type :point
    :frame (ensure-frame frame)
    :coords (vec coords)}))

(defn path
  "Create a path geometry from sequence of 3D points." 
  ([points] (path points {}))
  ([points {:keys [frame]}]
   (when (< (count points) 2)
     (throw (ex-info "A path requires at least two points" {:points points})))
   (let [pts (mapv vec points)
         segments (mapv vec (partition 2 1 pts))]
     {:type :path
      :frame (ensure-frame frame)
      :points pts
      :segments segments})))

(defn polygon
  "Create a 2D polygon (assumes planar coordinates)."
  ([vertices] (polygon vertices {}))
  ([vertices {:keys [frame]}]
   (when (< (count vertices) 3)
     (throw (ex-info "A polygon requires three or more vertices" {:vertices vertices})))
   {:type :polygon
    :frame (ensure-frame frame)
    :vertices (mapv vec vertices)}))

(defn field-of-view
  "Create a sensor field-of-view primitive."
  [{:keys [origin orientation h-fov-deg v-fov-deg range frame]
    :or {orientation frame/identity-quaternion
         range 0.0}}]
  (when (or (nil? h-fov-deg)
            (nil? v-fov-deg)
            (nil? origin)
            (neg? range)
            (<= h-fov-deg 0)
            (<= v-fov-deg 0))
    (throw (ex-info "Invalid field-of-view parameters"
                    {:range range :h-fov h-fov-deg :v-fov v-fov-deg})))
  {:type :field-of-view
   :frame (ensure-frame frame)
   :origin (vec origin)
   :orientation (frame/normalize-quaternion orientation)
   :h-fov-deg h-fov-deg
   :v-fov-deg v-fov-deg
   :range range})
