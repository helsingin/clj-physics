(ns physics.ops.safety
  "Operational safety checks: limits, separation, boundaries, and predictive lookahead.
   Survival Mode: Returns safe defaults (false/unsafe) on corrupt data rather than crashing."
  (:require [physics.core :as core]
            [physics.ops.kinematics :as k]
            [physics.ops.uncertainty :as unc]
            [malli.core :as m]))

;; --- Safety Utilities ---

(defn- ensure-v3 [v]
  (case (count v)
    3 v
    2 (conj v 0.0)
    v))

(defn- finite? [v]
  (cond
    (number? v) (Double/isFinite v)
    (vector? v) (every? #(and (number? %) (Double/isFinite %)) v)
    :else false))

(defn- v-mag-sq [v]
  (reduce + (map #(* % %) v)))

(defn- v-mag [v]
  (Math/sqrt (v-mag-sq v)))

(defn- v-dist-sq [a b]
  (let [a (ensure-v3 a) b (ensure-v3 b)
        dx (- (nth a 0 0.0) (nth b 0 0.0))
        dy (- (nth a 1 0.0) (nth b 1 0.0))
        dz (- (nth a 2 0.0) (nth b 2 0.0))]
    (+ (* dx dx) (* dy dy) (* dz dz))))

;; --- Dynamic Limits ---

(defn valid-speed?
  "Is |velocity| <= max-speed?"
  [velocity {:keys [max-speed]}]
  (if (and (finite? velocity) (number? max-speed))
    (<= (v-mag-sq (ensure-v3 velocity)) (* max-speed max-speed))
    false))

(defn valid-turn-rate?
  "Is |angular-velocity| <= max-turn-rate?"
  [state {:keys [max-turn-rate]}]
  (let [w (:angular-velocity state)]
    (if (and (finite? w) (number? max-turn-rate))
      (<= (v-mag-sq (ensure-v3 w)) (* max-turn-rate max-turn-rate))
      false)))

(defn check-dynamics
  "Check all dynamic limits. Returns set of violated keywords (e.g., #{:speed-limit})."
  [state limits]
  (let [violations (transient #{})]
    (when-not (valid-speed? (:velocity state) limits)
      (conj! violations :speed-limit))
    (when-not (valid-turn-rate? state limits)
      (conj! violations :turn-limit))
    (persistent! violations)))

;; --- Separation ---

(defn safe-separation?
  "Is dist(a, b) >= min-sep?
   Optimized: uses squared distance."
  [pos-a pos-b min-sep]
  (if (and (finite? pos-a) (finite? pos-b) (number? min-sep))
    (>= (v-dist-sq pos-a pos-b) (* min-sep min-sep))
    false))

;; --- Boundaries & Geometry Helpers ---

(defn- point-to-segment-dist-sq
  "Shortest squared distance between point P and line segment AB."
  [[px py] [[ax ay] [bx by]]]
  (let [dx (- bx ax)
        dy (- by ay)
        l2 (+ (* dx dx) (* dy dy))]
    (if (zero? l2)
      (+ (* (- px ax) (- px ax)) (* (- py ay) (- py ay)))
      (let [t (max 0.0 (min 1.0 (/ (+ (* (- px ax) dx) (* (- py ay) dy)) l2)))]
        (+ (* (- px (+ ax (* t dx))) (- px (+ ax (* t dx))))
           (* (- py (+ ay (* t dy))) (- py (+ ay (* t dy)))))))))

(defn- distance-to-polygon-boundary-sq
  "Minimum squared distance from point to any edge of the polygon."
  [point polygon]
  (let [p2 [(nth point 0) (nth point 1)]
        n (count polygon)]
    (apply min
           (for [i (range n)]
             (let [p1 (nth polygon i)
                   p2-edge (nth polygon (mod (inc i) n))]
               (point-to-segment-dist-sq p2 [p1 p2-edge]))))))

(defn inside-polygon-2d?
  "Is 3D point (projected to XY) inside 2D polygon vertices?
   Ignores Z-axis. Uses Ray Casting + Edge Distance Check for robustness."
  [point polygon]
  (if (and (finite? point) (seq polygon))
    (or (< (distance-to-polygon-boundary-sq point polygon) 1.0e-9)
        (let [x (nth point 0)
              y (nth point 1)
              n (count polygon)]
          (loop [i 0
                 j (dec n)
                 inside? false]
            (if (< i n)
              (let [[xi yi] (nth polygon i)
                    [xj yj] (nth polygon j)
                    intersect? (and (> yi y) (<= yj y))
                    intersect? (or (and (<= yi y) (> yj y)) intersect?)
                    intersect? (and intersect?
                                    (< x (+ xi (/ (* (- xj xi) (- y yi))
                                                  (- yj yi)))))]
                (recur (inc i) i (if intersect? (not inside?) inside?)))
              inside?))))
    false))

(defn inside-prism?
  "Is 3D point inside a volume defined by a 2D polygon and altitude limits?
   :min-alt and :max-alt are optional (default -Inf, +Inf)."
  [point polygon {:keys [min-alt max-alt]}]
  (let [z (nth (ensure-v3 point) 2 0.0)]
    (and (inside-polygon-2d? point polygon)
         (or (nil? min-alt) (>= z min-alt))
         (or (nil? max-alt) (<= z max-alt)))))

;; --- Predictive Safety ---

(defn predictive-safety?
  "Propagate state-a (and optionally state-b) forward to check for separation violations.
   Returns { :safe? bool :violation key :time-of-violation t }"
  [state-a state-b constraints {:keys [horizon dt] :or {horizon 5.0 dt 0.5}}]
  (let [min-sep (:min-separation constraints 0.0)
        steps (long (/ horizon dt))]
    (loop [t 0.0
           s-a state-a
           s-b state-b
           i 0]
      (if (> i steps)
        {:safe? true}
        (if (not (safe-separation? (:position s-a) (:position s-b) min-sep))
          {:safe? false 
           :violation :separation-violation
           :time-of-violation t}
          (recur (+ t dt)
                 (k/propagate s-a dt)
                 (k/propagate s-b dt)
                 (inc i)))))))

(defn conservative-predictive-safety?
  "Propagate states AND uncertainty bubbles forward.
   Checks if error bubbles overlap or breach separation.
   Condition: dist(a, b) >= min-sep + error_a(t) + error_b(t)
   
   Requires uncertainty maps for a and b: {:pos :vel :acc-unknown}"
  [state-a state-b unc-a unc-b constraints {:keys [horizon dt] :or {horizon 5.0 dt 0.5}}]
  (let [min-sep (:min-separation constraints 0.0)
        steps (long (/ horizon dt))]
    (loop [t 0.0
           s-a state-a
           s-b state-b
           i 0]
      (if (> i steps)
        {:safe? true}
        (let [err-a (unc/error-radius unc-a t)
              err-b (unc/error-radius unc-b t)
              required-sep (+ min-sep err-a err-b)]
          (if (not (safe-separation? (:position s-a) (:position s-b) required-sep))
            {:safe? false
             :violation :uncertainty-violation
             :time-of-violation t
             :margin (- (Math/sqrt (v-dist-sq (:position s-a) (:position s-b))) required-sep)}
            (recur (+ t dt)
                   (k/propagate s-a dt)
                   (k/propagate s-b dt)
                   (inc i))))))))

(defn predictive-boundary?
  "Propagate state forward to check for boundary exit.
   Checks against :polygon (2D) and optionally :min-alt/:max-alt.
   Returns { :safe? bool :violation key :time-of-violation t }"
  [state constraints {:keys [horizon dt] :or {horizon 5.0 dt 0.5}}]
  (let [poly (:polygon constraints)
        steps (long (/ horizon dt))]
    (loop [t 0.0
           s state
           i 0]
      (if (> i steps)
        {:safe? true}
        (if (not (inside-prism? (:position s) poly constraints))
          {:safe? false
           :violation :boundary-exit
           :time-of-violation t}
          (recur (+ t dt)
                 (k/propagate s dt)
                 (inc i)))))))

(defn conservative-predictive-boundary?
  "Check if the error bubble of a state breaches the boundary or altitude limits.
   Bubble radius is calculated using uncertainty-map at each time step.
   Respects :margin in constraints (static buffer inflation).
   Returns { :safe? bool :violation key :time-of-violation t }"
  [state uncertainty polygon constraints {:keys [horizon dt] :or {horizon 5.0 dt 0.5}}]
  (let [steps (long (/ horizon dt))
        static-margin (:margin constraints 0.0)]
    (loop [t 0.0
           s state
           i 0]
      (if (> i steps)
        {:safe? true}
        (let [pos (:position s)
              r (unc/error-radius uncertainty t)
              effective-r (+ r static-margin)
              r2 (* effective-r effective-r)
              
              ;; 1. Inside check (raw polygon)
              inside? (inside-prism? pos polygon constraints)
              ;; 2. Margin check (distance to boundary vs effective radius)
              dist2 (distance-to-polygon-boundary-sq pos polygon)
              ;; 3. Altitude margin check
              z (nth (ensure-v3 pos) 2 0.0)
              z-ok? (and (or (nil? (:min-alt constraints)) (>= (- z effective-r) (:min-alt constraints)))
                         (or (nil? (:max-alt constraints)) (<= (+ z effective-r) (:max-alt constraints))))]
          
          (if (or (not inside?) (< dist2 r2) (not z-ok?))
            {:safe? false
             :violation :conservative-boundary-violation
             :time-of-violation t}
            (recur (+ t dt)
                   (k/propagate s dt)
                   (inc i))))))))