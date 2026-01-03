(ns physics.integrators
  "Deterministic numerical integrators for state propagation."
  (:require [clojure.math :as math]))

(defn- v+ [& vs]
  (apply mapv + vs))

(defn- scale [k v]
  (mapv #(* k %) v))

(defn rk4
  "Classical Runge-Kutta (order 4) for fixed step problems.

  Options map:
    :derivative   function (t state) -> state derivative vector
    :initial-state vector of initial state values
    :t0          initial time
    :dt          step size (seconds)
    :steps       number of steps to integrate
    :store?      if true, retains history for inspection

  Returns {:state final-state :time final-time :history [...]}"
  [{:keys [derivative initial-state t0 dt steps store?]
    :or {t0 0.0 store? false}}
   ]
  (let [derivative (or derivative (fn [_ _] (repeat (count initial-state) 0.0)))
        dt (double dt)
        steps (long steps)
        init-state (vec initial-state)
        history (transient (if store? [{:t t0 :state init-state}] []))]
    (loop [i 0
           t t0
           state init-state
           hist history]
      (if (>= i steps)
        {:state state
         :time t
         :history (persistent! hist)}
        (let [k1 (derivative t state)
              k2 (derivative (+ t (* 0.5 dt)) (v+ state (scale (* 0.5 dt) k1)))
              k3 (derivative (+ t (* 0.5 dt)) (v+ state (scale (* 0.5 dt) k2)))
              k4 (derivative (+ t dt) (v+ state (scale dt k3)))
              delta (scale (/ dt 6.0)
                           (v+ k1 (scale 2.0 k2) (scale 2.0 k3) k4))
              new-state (v+ state delta)
              new-hist (if store? (conj! hist {:t (+ t dt) :state new-state}) hist)]
          (recur (inc i) (+ t dt) new-state new-hist))))))

(def rkf45-coefficients
  {:a2 (/ 1.0 4.0)
   :a3 (/ 3.0 8.0)
   :a4 (/ 12.0 13.0)
   :a5 1.0
   :a6 0.5
   :b21 (/ 1.0 4.0)
   :b31 (/ 3.0 32.0)
   :b32 (/ 9.0 32.0)
   :b41 (/ 1932.0 2197.0)
   :b42 (/ -7200.0 2197.0)
   :b43 (/ 7296.0 2197.0)
   :b51 (/ 439.0 216.0)
   :b52 -8.0
   :b53 (/ 3680.0 513.0)
   :b54 (/ -845.0 4104.0)
   :b61 (/ -8.0 27.0)
   :b62 2.0
  :b63 (/ -3544.0 2565.0)
   :b64 (/ 1859.0 4104.0)
   :b65 (/ -11.0 40.0)
   :c4 [(/ 25.0 216.0) 0.0 (/ 1408.0 2565.0) (/ 2197.0 4104.0) (- (/ 1.0 5.0)) 0.0]
   :c5 [(/ 16.0 135.0) 0.0 (/ 6656.0 12825.0) (/ 28561.0 56430.0) (- (/ 9.0 50.0)) (/ 2.0 55.0)]})

(defn rkf45
  "Adaptive fifth-order Runge-Kutta-Fehlberg integrator.

  Options map:
    :derivative (t state) -> derivative vector
    :initial-state vector
    :t-span [t0 tf]
    :rtol relative tolerance
    :atol absolute tolerance
    :h-init optional initial step size

  Returns {:state final-state :time tf :steps history}."
  [{:keys [derivative initial-state t-span rtol atol h-init store?]
    :or {rtol 1e-6 atol 1e-9 store? false}}
   ]
  (let [[t0 tf] t-span
        derivative (or derivative (fn [_ _] (repeat (count initial-state) 0.0)))
        init-state (vec initial-state)
        safety 0.9
        min-factor 0.2
        max-factor 1.1
        h0 (or h-init (/ (max 1e-9 (- tf t0)) 10000.0))
        {:keys [a2 a3 a4 a5 a6 b21 b31 b32 b41 b42 b43 b51 b52 b53 b54 b61 b62 b63 b64 b65 c4 c5]} rkf45-coefficients
        history (transient (if store? [{:t t0 :state init-state}] []))]
    (loop [t t0
           h h0
           state init-state
           hist history
           step-hist (transient [])]
      (if (>= t tf)
        {:state (mapv - state init-state)
         :final-state state
         :time t
         :steps (persistent! step-hist)
         :history (persistent! hist)}
        (let [h (min h (- tf t))
              k1 (derivative t state)
              k2 (derivative (+ t (* a2 h)) (v+ state (scale (* h b21) k1)))
              k3 (derivative (+ t (* a3 h)) (v+ state (scale (* h b31) k1) (scale (* h b32) k2)))
              k4 (derivative (+ t (* a4 h)) (v+ state (scale (* h b41) k1) (scale (* h b42) k2) (scale (* h b43) k3)))
              k5 (derivative (+ t (* a5 h)) (v+ state (scale (* h b51) k1) (scale (* h b52) k2) (scale (* h b53) k3) (scale (* h b54) k4)))
              k6 (derivative (+ t (* a6 h)) (v+ state (scale (* h b61) k1) (scale (* h b62) k2) (scale (* h b63) k3) (scale (* h b64) k4) (scale (* h b65) k5)))
              y4 (v+ state (scale h (v+ (scale (nth c4 0) k1)
                                          (scale (nth c4 1) k2)
                                          (scale (nth c4 2) k3)
                                          (scale (nth c4 3) k4)
                                          (scale (nth c4 4) k5)
                                          (scale (nth c4 5) k6))))
              y5 (v+ state (scale h (v+ (scale (nth c5 0) k1)
                                          (scale (nth c5 1) k2)
                                          (scale (nth c5 2) k3)
                                          (scale (nth c5 3) k4)
                                          (scale (nth c5 4) k5)
                                          (scale (nth c5 5) k6))))
              error (mapv - y5 y4)
              scale-factor (max atol (* rtol (math/sqrt (reduce + (map #(* % %) y5)))))
              err-norm (if (zero? scale-factor)
                         (math/sqrt (reduce + (map #(* % %) error)))
                         (/ (math/sqrt (reduce + (map #(* % %) error))) scale-factor))]
          (if (<= err-norm 1.0)
            (let [new-t (+ t h)
                  new-state (mapv identity y5)
                  new-hist (if store? (conj! hist {:t new-t :state new-state}) hist)
                  factor (-> (* safety (math/pow (max err-norm 1e-12) -0.2))
                             (max min-factor)
                             (min max-factor))]
              (recur new-t (* h factor) new-state new-hist (conj! step-hist h)))
            (let [factor (-> (* safety (math/pow (max err-norm 1e-12) -0.25))
                             (max min-factor)
                             (min max-factor))]
              (recur t (* h factor) state hist step-hist))))))))
