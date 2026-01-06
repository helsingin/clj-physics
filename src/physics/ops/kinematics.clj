(ns physics.ops.kinematics
  "High-assurance kinematic propagation for field operations.
   Features 'Survival Mode' and 'Risk Awareness': propagates both state and uncertainty bounds."
  (:require [physics.core :as core]
            [malli.core :as m]))

;; --- Operational Constants ---

(def ^:const EPSILON_TIME 1e-9)
(def ^:const MAX_DT 10.0)
(def ^:const MAX_LATENCY 2.0)
(def ^:const MAX_ACCEL_G 100.0)
(def ^:const G0 9.80665)
(def ^:const ACCEL_DECAY_THRESHOLD 1.5) 

(def Vector3 [:vector {:size 3} number?])
(def UncertaintyMap [:map 
                     [:pos number?] 
                     [:vel {:optional true} number?] 
                     [:acc-unknown {:optional true} number?]])

(def KinematicState
  [:map
   [:position Vector3]
   [:velocity Vector3]
   [:frame {:optional true} :keyword]
   [:acceleration {:optional true} Vector3]
   [:uncertainty {:optional true} UncertaintyMap]
   [:limits {:optional true} [:map [:max-speed {:optional true} number?]]]])

;; --- Math Helpers ---

(defn- ensure-v3 [v]
  (case (count v)
    3 v
    2 (conj v 0.0)
    (throw (ex-info "Invalid vector dimension" {:v v}))))

(defn- v3+ [a b] 
  [(+ (double (nth a 0)) (double (nth b 0)))
   (+ (double (nth a 1)) (double (nth b 1)))
   (+ (double (nth a 2)) (double (nth b 2)))])

(defn- v3scale [^double k v] 
  [(* k (double (nth v 0)))
   (* k (double (nth v 1)))
   (* k (double (nth v 2)))])

(defn- v3mag [v] (Math/sqrt (reduce + (map #(* % %) v))))

(defn finite? [v] (and (vector? v) (every? #(and (number? %) (Double/isFinite %)) v)))

(defn- clamp-v3 [v max-mag]
  (let [m (v3mag v)] (if (> m max-mag) (v3scale (/ max-mag m) v) v)))

(defn grow-uncertainty
  "Propagate uncertainty bounds forward in time.
   E_pos_new = E_pos + E_vel*dt + 0.5*E_acc*dt^2"
  [{:keys [pos vel acc-unknown] :or {pos 0.0 vel 0.0 acc-unknown 0.0}} dt]
  (let [t (double (Math/abs dt))]
    {:pos (+ pos (* vel t) (* 0.5 acc-unknown t t))
     :vel vel
     :acc-unknown acc-unknown}))

;; --- Validation ---

(defn validate-frame! 
  [label state-a state-b]
  (let [f1 (:frame state-a :world)
        f2 (:frame state-b :world)]
    (when-not (= f1 f2)
      (throw (ex-info (str "Coordinate frame mismatch in " label)
                      {:expected f1 :actual f2})))))

;; --- Propagators ---

(defn constant-velocity
  "Robust CV propagator. Iterates if dt > MAX_DT."
  [state dt]
  (if (or (not (finite? (:position state))) (not (finite? (:velocity state))))
    (assoc state :status :degraded-static)
    (let [dt (double dt)]
      (if (> dt MAX_DT)
        (recur (constant-velocity state MAX_DT) (- dt MAX_DT))
        (let [dt (max 0.0 dt)
              p (ensure-v3 (:position state))
              v (ensure-v3 (:velocity state))
              new-p (v3+ p (v3scale dt v))
              unc (:uncertainty state)]
          (cond-> (if (finite? new-p) (assoc state :position new-p :velocity v) state)
            unc (update :uncertainty grow-uncertainty dt)))))))

(defn constant-acceleration
  "Robust CA propagator. Iterates if dt > MAX_DT."
  [state acceleration dt]
  (let [acceleration (if acceleration (ensure-v3 acceleration) nil)
        acc-mag (if acceleration (v3mag acceleration) 0.0)]
    (cond
      (or (not (finite? acceleration)) (> acc-mag (* MAX_ACCEL_G G0)))
      (constant-velocity state dt)

      (> (double dt) MAX_DT)
      (recur (constant-acceleration state acceleration MAX_DT) acceleration (- (double dt) MAX_DT))

      :else
      (let [dt (max 0.0 (double dt))
            decay (if (> dt ACCEL_DECAY_THRESHOLD) (/ ACCEL_DECAY_THRESHOLD dt) 1.0)
            eff-acc (v3scale decay acceleration)
            p0 (ensure-v3 (:position state))
            v0 (ensure-v3 (:velocity state))]
        (if (or (not (finite? p0)) (not (finite? v0)))
          state
          (let [vt (v3scale dt v0)
                half-at2 (v3scale (* 0.5 dt dt) eff-acc)
                new-p (v3+ p0 (v3+ vt half-at2))
                raw-v (v3+ v0 (v3scale dt eff-acc))
                max-s (get-in state [:limits :max-speed])
                new-v (if (and max-s (finite? raw-v)) (clamp-v3 raw-v max-s) raw-v)
                unc (:uncertainty state)]
            (cond-> (if (and (finite? new-p) (finite? new-v))
                      (assoc state :position new-p :velocity new-v :acceleration eff-acc)
                      (constant-velocity state dt))
              unc (update :uncertainty grow-uncertainty dt))))))))

;; --- API ---

(defn propagate
  "Risk-Aware Propagation.
   Updates both the platform state and the associated uncertainty bounds."
  ([state dt]
   (cond
     (not (m/validate KinematicState state)) state
     (< (Math/abs (double dt)) EPSILON_TIME) state
     (:acceleration state) (constant-acceleration state (:acceleration state) dt)
     :else (constant-velocity state dt)))
  ([state dt acceleration]
   (cond
     (not (m/validate KinematicState state)) state
     (< (Math/abs (double dt)) EPSILON_TIME) state
     :else (constant-acceleration state acceleration dt))))

(defn compensate-latency
  [state cmd-time current-time]
  (let [raw-dt (- (double current-time) (double cmd-time))
        dt (max 0.0 (min raw-dt MAX_LATENCY))]
    (propagate state dt)))
