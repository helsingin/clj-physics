(ns physics.ops.intercept
  "Tactical intercept solvers with survival-grade robustness.
   Handles impossible geometries, zero-speed singularities, and numerical instability."
  (:require [physics.core :as core]
            [physics.ops.kinematics :as k]
            [malli.core :as m]))

;; --- Constants ---
(def ^:const EPSILON 1e-9)
(def ^:const DEFAULT_HORIZON 3600.0) ;; 1 hour default limit

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

(defn- v-sub [a b] (mapv - a b))
(defn- v-add [a b] (mapv + a b))
(defn- v-scale [k v] (mapv #(* (double k) (double %)) v))
(defn- v-mag [v] (Math/sqrt (reduce + (map #(* % %) v))))
(defn- v-norm [v]
  (let [m (v-mag v)]
    (if (> m EPSILON) (v-scale (/ 1.0 m) v) [0.0 0.0 0.0])))
(defn- dot [a b] (reduce + (map * a b)))

;; --- Solvers ---

(defn time-to-go
  ([origin target speed] (time-to-go origin target speed {}))
  ([origin target speed {:keys [max-speed]}]
   (if (and (finite? origin) (finite? target) (finite? speed))
     (let [dist (v-mag (v-sub (ensure-v3 target) (ensure-v3 origin)))
           eff-speed (if (and max-speed (> speed max-speed)) max-speed speed)]
       (cond
         (< dist EPSILON) {:ttg 0.0 :status :valid}
         (< eff-speed EPSILON) {:ttg Double/POSITIVE_INFINITY :status :infinite}
         :else {:ttg (/ dist eff-speed) 
                :status (if (and max-speed (> speed max-speed)) :limited :valid)}))
     {:ttg Double/POSITIVE_INFINITY :status :error})))

(defn closure-rate
  [p1 v1 p2 v2]
  (k/validate-frame! "closure-rate" p1 p2)
  (if (and (finite? p1) (finite? v1) (finite? p2) (finite? v2))
    (let [r (v-sub (ensure-v3 p2) (ensure-v3 p1))
          v-rel (v-sub (ensure-v3 v2) (ensure-v3 v1))
          dist (v-mag r)]
      (if (< dist EPSILON)
        {:closure 0.0 :dist 0.0 :status :coincident}
        (let [r-hat (v-scale (/ 1.0 dist) r)
              proj (dot v-rel r-hat)]
          {:closure (- proj) :dist dist :status :valid})))
    {:closure 0.0 :dist 0.0 :status :error}))

(defn linear-intercept
  "Solve for minimum time intercept of a CV target.
   Uses Citardauq Formula for numerical stability.
   Returns :no-solution if time exceeds :max-time (default 1 hour)."
  [p-i s-i p-t v-t & {:keys [max-time] :or {max-time DEFAULT_HORIZON}}]
  (k/validate-frame! "linear-intercept" p-i p-t)
  (if (and (finite? p-i) (finite? s-i) (finite? p-t) (finite? v-t))
    (let [p-i (ensure-v3 p-i) p-t (ensure-v3 p-t) v-t (ensure-v3 v-t)
          D (v-sub p-t p-i)
          D-mag-sq (dot D D)
          s-t-sq (dot v-t v-t)
          s-i-sq (* s-i s-i)
          a (- s-i-sq s-t-sq)
          b (* -2.0 (dot D v-t))
          c (- D-mag-sq)]
      (if (< (Math/abs a) EPSILON)
        (if (< (Math/abs b) EPSILON)
          {:status :no-solution}
          (let [t (/ (- c) b)]
            (if (and (pos? t) (<= t max-time))
              {:time t :intercept-point (v-add p-t (v-scale t v-t)) :status :valid}
              {:status :no-solution})))
        (let [discriminant (- (* b b) (* 4.0 a c))]
          (if (neg? discriminant)
            {:status :no-solution}
            (let [sqrt-d (Math/sqrt discriminant)
                  q (* -0.5 (+ b (if (neg? b) (- sqrt-d) sqrt-d)))
                  t1 (/ q a)
                  t2 (/ c q)
                  valid-times (filter #(and (> % EPSILON) (<= % max-time)) [t1 t2])]
              (if (empty? valid-times)
                {:status :no-solution}
                (let [t (apply min valid-times)]
                  {:time t :intercept-point (v-add p-t (v-scale t v-t)) :status :valid})))))))
    {:status :error}))

;; --- Guidance Logic Generators ---

(defn pure-pursuit
  [p-i p-t]
  (k/validate-frame! "pure-pursuit" p-i p-t)
  (if (and (finite? p-i) (finite? p-t))
    {:aim-vector (v-norm (v-sub (ensure-v3 p-t) (ensure-v3 p-i))) :status :valid}
    {:aim-vector [1.0 0.0 0.0] :status :error}))

(defn lead-pursuit
  [p-i s-i p-t v-t]
  (k/validate-frame! "lead-pursuit" p-i p-t)
  (let [sol (linear-intercept p-i s-i p-t v-t)]
    (if (= :valid (:status sol))
      {:aim-vector (v-norm (v-sub (:intercept-point sol) (ensure-v3 p-i)))
       :status :valid
       :time (:time sol)}
      (assoc (pure-pursuit p-i p-t) :status :fallback-to-pure))))

(defn intercept-bounds
  [p-i s-i p-t v-t uncertainty-radius]
  (let [nominal (linear-intercept p-i s-i p-t v-t)]
    (if (= :valid (:status nominal))
      (let [t-nom (:time nominal)
            dt (/ (double uncertainty-radius) (double s-i))]
        {:min-time (max 0.0 (- t-nom dt))
         :max-time (+ t-nom dt)
         :nominal-time t-nom
         :status :valid})
      {:status (:status nominal)})))

(defn guidance
  [mode p-i s-i p-t v-t]
  (case mode
    :pure (pure-pursuit p-i p-t)
    :lead (lead-pursuit p-i s-i p-t v-t)
    (pure-pursuit p-i p-t)))
