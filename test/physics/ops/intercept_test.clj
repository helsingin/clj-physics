(ns physics.ops.intercept-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.ops.intercept :as intercept]
            [physics.core :as core]))

(defn- ≈ [a b tol]
  (< (Math/abs (- (double a) (double b))) tol))

(defn- v≈ [v1 v2 tol]
  (every? (fn [[x1 x2]] (< (Math/abs (- x1 x2)) tol)) (map vector v1 v2)))

(deftest ttg-test
  (testing "Standard TTG"
    ;; dist = 100, speed = 10 -> t = 10
    (is (≈ 10.0 (:ttg (intercept/time-to-go [0 0 0] [100 0 0] 10.0)) 1e-6)))

  (testing "Zero speed (Survival Mode)"
    ;; Should not divide by zero or throw. Should return Infinity or very large number.
    (let [result (intercept/time-to-go [0 0 0] [100 0 0] 0.0)]
      (is (= :infinite (:status result)))
      (is (= Double/POSITIVE_INFINITY (:ttg result)))))

  (testing "Already there"
    (let [result (intercept/time-to-go [10 10 10] [10 10 10] 50.0)]
      (is (≈ 0.0 (:ttg result) 1e-6))))

  (testing "TTG with Speed Limits"
    (let [p-i [0 0 0] p-t [100 0 0]
          speed 100.0
          limits {:max-speed 10.0}]
      ;; dist 100, speed 100 -> t=1.0. BUT limited to 10 -> t=10.0
      (let [result (intercept/time-to-go p-i p-t speed limits)]
        (is (= :limited (:status result)))
        (is (≈ 10.0 (:ttg result) 1e-6))))))

(deftest pursuit-generator-test
  (testing "Pure vs Lead Pursuit"
    (let [p-i [0 0 0] s-i 20.0 ;; Faster than target
          p-t [0 100 0] v-t [10 0 0]] ;; Target 100m up, moving right
      ;; Pure pursuit: Aim UP [0 1 0]
      (is (v≈ [0 1 0] (:aim-vector (intercept/guidance :pure p-i s-i p-t v-t)) 1e-6))
      ;; Lead pursuit: Aim UP-RIGHT
      (let [lead (intercept/guidance :lead p-i s-i p-t v-t)]
        (is (= :valid (:status lead)))
        (is (> (first (:aim-vector lead)) 0.0))
        (is (> (second (:aim-vector lead)) 0.0))))))

(deftest closure-rate-test
  (testing "Head-on closure"
    (let [p1 [0 0 0] v1 [10 0 0]
          p2 [100 0 0] v2 [-10 0 0]
          ;; Relative v = v2 - v1 = [-20 0 0].
          ;; LOS p2 - p1 = [100 0 0] (Right).
          ;; Projection of [-20 0 0] on [1 0 0] is -20.
          ;; Closure is positive closing speed? Usually defined as -Vrel_los.
          ;; Let's assume standard definition: positive = closing.
          result (intercept/closure-rate p1 v1 p2 v2)]
      (is (≈ 20.0 (:closure result) 1e-6))))

  (testing "Opening rate (running away)"
    (let [p1 [0 0 0] v1 [0 0 0]
          p2 [100 0 0] v2 [10 0 0]
          result (intercept/closure-rate p1 v1 p2 v2)]
      (is (≈ -10.0 (:closure result) 1e-6)))))

(deftest linear-intercept-test
  (testing "Stationary Target"
    (let [p-i [0 0 0] s-i 10.0
          p-t [100 0 0] v-t [0 0 0]
          ;; t = 100 / 10 = 10
          sol (intercept/linear-intercept p-i s-i p-t v-t)]
      (is (= :valid (:status sol)))
      (is (≈ 10.0 (:time sol) 1e-6))
      (is (v≈ [100 0 0] (:intercept-point sol) 1e-6))))

  (testing "Moving Target (90 deg intercept)"
    (let [p-i [0 0 0] s-i 5.0
          p-t [30 40 0] v-t [-3 0 0] ;; Target moving left towards y-axis
          ;; Solve manually? Or trust geometry.
          ;; 3-4-5 triangle.
          ;; If t=10: Target at [0 40 0]. Interceptor at [0 0 0]. Dist=40. Speed=5. 5*10=50 != 40.
          ;; Let's rely on the solver.
          sol (intercept/linear-intercept p-i s-i p-t v-t)]
      (is (= :valid (:status sol)))
      (is (pos? (:time sol)))))

  (testing "Impossible Intercept (Target too fast)"
    (let [p-i [0 0 0] s-i 10.0
          p-t [100 0 0] v-t [20 0 0] ;; Target fleeing at 20, we calculate at 10
          sol (intercept/linear-intercept p-i s-i p-t v-t)]
      (is (= :no-solution (:status sol)))
      (is (nil? (:time sol)))))

  (testing "Degraded Input (NaN)"
    (let [sol (intercept/linear-intercept [0 0 0] 10.0 [Double/NaN 0 0] [0 0 0])]
      (is (= :error (:status sol))))))

(deftest lead-angle-test
  (testing "Calculates aiming vector"
    (let [p-i [0 0 0] s-i 10.0
          p-t [100 0 0] v-t [0 0 0]
          res (intercept/lead-pursuit p-i s-i p-t v-t)]
      ;; Should aim straight at target [1 0 0]
      (is (v≈ [1.0 0.0 0.0] (:aim-vector res) 1e-6))))

  (testing "Impossible lead falls back to Pure Pursuit"
    (let [p-i [0 0 0] s-i 10.0
          p-t [100 0 0] v-t [20 0 0] ;; Fleeing fast
          res (intercept/lead-pursuit p-i s-i p-t v-t)]
      ;; Should fallback to looking at target
      (is (= :fallback-to-pure (:status res)))
      (is (v≈ [1.0 0.0 0.0] (:aim-vector res) 1e-6)))))
