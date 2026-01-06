(ns physics.ops.uncertainty-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.ops.uncertainty :as unc]
            [physics.core :as core]))

(defn- ≈ [a b tol]
  (< (Math/abs (- (double a) (double b))) tol))

(deftest kinematic-error-growth-test
  (testing "Static Error (No time)"
    (let [initial-error {:pos 10.0 :vel 1.0 :acc-unknown 0.0}]
      ;; At t=0, error is just position error
      (is (= 10.0 (unc/error-radius initial-error 0.0)))))

  (testing "Linear Drift (Velocity uncertainty)"
    (let [initial-error {:pos 0.0 :vel 2.0 :acc-unknown 0.0}]
      ;; At t=5, error = 2.0 * 5 = 10.0
      (is (= 10.0 (unc/error-radius initial-error 5.0)))))

  (testing "Maneuver Uncertainty (Worst-case)"
    (let [initial-error {:pos 0.0 :vel 0.0 :acc-unknown 10.0} ;; Target can pull 10m/s^2
          t 2.0]
      ;; E = 0.5 * a * t^2 = 0.5 * 10 * 4 = 20.0
      (is (= 20.0 (unc/error-radius initial-error t)))))
  
  (testing "Combined Error Budget"
    (let [err {:pos 10.0 :vel 5.0 :acc-unknown 2.0}
          t 3.0]
      ;; E = 10 + (5*3) + (0.5*2*9) = 10 + 15 + 9 = 34.0
      (is (= 34.0 (unc/error-radius err t))))))

(deftest safety-bubble-test
  (testing "Bubble Expansion"
    (let [state {:position [0 0 0] :velocity [100 0 0]}
          uncertainty {:pos 10.0 :vel 1.0 :acc-unknown 0.5}
          horizon 2.0]
      ;; Bubble at t=2.0
      ;; Center: [200 0 0] (Kinematic projection)
      ;; Radius: 10 + 1*2 + 0.5*0.5*4 = 10 + 2 + 1 = 13.0
      (let [bubble (unc/safety-bubble state uncertainty horizon)]
        (is (= 13.0 (:radius bubble)))
        ;; Check 1D projection for simplicity
        (is (= 200.0 (first (:center bubble))))))))

(deftest probabilistic-ttg-test
  (testing "TTG Bounds"
    (let [origin [0 0 0]
          target [100 0 0]
          speed 10.0
          uncertainty {:pos 5.0}] ;; Target is somewhere within +/- 5m
      
      ;; Min TTG: Target is 5m closer (95m) -> 9.5s
      ;; Max TTG: Target is 5m further (105m) -> 10.5s
      (let [bounds (unc/ttg-bounds origin target speed uncertainty)]
        (is (= 9.5 (:min-ttg bounds)))
        (is (= 10.5 (:max-ttg bounds))))))

  (testing "Uncertainty Overlap (Min TTG = 0)"
    (let [origin [0 0 0]
          target [5 0 0]
          speed 1.0
          uncertainty {:pos 10.0}] ;; Target error covers origin
      (let [bounds (unc/ttg-bounds origin target speed uncertainty)]
        (is (= 0.0 (:min-ttg bounds))) ;; Could be here right now
        (is (= 15.0 (:max-ttg bounds)))))))

(deftest phase4-compliance-test
  (testing "Linear Covariance Growth"
    (is (= 15.0 (unc/linear-error-growth 10.0 1.0 5.0))))

  (testing "Buffer Inflation"
    (let [pt {:type :point :coords [0 0 0]}
          inflated (unc/inflate-geometry pt 5.0)]
      (is (= :sphere (:type inflated)))
      (is (= 5.0 (:radius inflated))))
    (let [poly {:type :polygon :vertices [[0 0] [10 0]]}
          inflated (unc/inflate-geometry poly 2.0)]
      (is (= 2.0 (:margin inflated)))))

  (testing "TTG with Speed Variance"
    (let [origin [0 0 0]
          target [100 0 0]
          speed 10.0
          unc   {:pos 0.0 :vel 2.0}] ;; Speed is 10 +/- 2
      ;; Min TTG: 100 / 12 = 8.33
      ;; Max TTG: 100 / 8 = 12.5
      (let [res (unc/ttg-bounds origin target speed unc)]
        (is (≈ 8.333333 (:min-ttg res) 1e-6))
        (is (≈ 12.5 (:max-ttg res) 1e-6))))))

