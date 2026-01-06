(ns physics.ops.uncertainty
  "Risk-aware uncertainty propagation.
   Includes geometric buffer inflation and probabilistic time-to-go bounds."
  (:require [physics.core :as core]
            [physics.ops.kinematics :as k]))

;; --- Constants ---
(def ^:const EPSILON 1e-9)

;; --- Core Growth Models ---

(defn linear-error-growth
  "Lightweight linear expansion: R(t) = R0 + alpha * t"
  [r0 alpha t]
  (+ (double r0) (* (double alpha) (Math/abs (double t)))))

(defn error-radius
  "Calculate worst-case position error at time t.
   Delegates to kinematics uncertainty propagator (Quadratic)."
  [uncertainty-map t]
  (:pos (k/grow-uncertainty uncertainty-map t)))

;; --- Probabilistic TTG ---

(defn ttg-bounds
  "Calculate Time-To-Go bounds considering BOTH position and speed uncertainty.
   uncertainty-map: {:pos <m> :vel <m/s>}
   Returns {:min-ttg <t> :max-ttg <t> :nominal-ttg <t>}"
  [origin target nominal-speed uncertainty]
  (let [dist (core/magnitude (mapv - target origin))
        e-p (:pos uncertainty 0.0)
        e-v (:vel uncertainty 0.0)
        
        ;; Worst case speed variance
        min-s (max EPSILON (- nominal-speed e-v))
        max-s (+ nominal-speed e-v)
        
        ;; Min Time: Minimum distance at Maximum speed
        min-ttg (/ (max 0.0 (- dist e-p)) max-s)
        ;; Max Time: Maximum distance at Minimum speed
        max-ttg (if (< min-s EPSILON) Double/POSITIVE_INFINITY (/ (+ dist e-p) min-s))]
    
    {:min-ttg min-ttg
     :max-ttg max-ttg
     :nominal-ttg (if (zero? nominal-speed) Double/POSITIVE_INFINITY (/ dist nominal-speed))}))

;; --- Buffer Inflation (Geometry Wrapping) ---

(defn inflate-geometry
  "Wrap a physics.spatial.geometry record with a safety margin.
   Works on :point, :path, and :polygon types."
  [geom margin]
  (let [m (double margin)]
    (case (:type geom)
      :point   (assoc geom :radius m :type :sphere) ;; Points inflate to spheres
      :path    (assoc geom :margin m)               ;; Paths inflate to corridors
      :polygon (assoc geom :margin m)               ;; Polygons inflate perimeters
      (assoc geom :margin m))))

;; --- Bubbles ---

(defn safety-bubble
  [state uncertainty t]
  (let [projected-state (k/propagate state t)
        radius (error-radius uncertainty t)]
    {:center (:position projected-state)
     :radius radius}))