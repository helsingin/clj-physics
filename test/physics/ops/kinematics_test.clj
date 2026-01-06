(ns physics.ops.kinematics-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.ops.kinematics :as k]
            [physics.core :as core]))

(defn- v≈ [v1 v2 tol]
  (every? (fn [[x1 x2]] (< (Math/abs (- x1 x2)) tol)) (map vector v1 v2)))

(deftest constant-velocity-test
  (testing "1D motion"
    (let [p0 [0.0 0.0 0.0]
          v0 [10.0 0.0 0.0]
          dt 2.0
          expected-p [20.0 0.0 0.0]]
      (is (v≈ expected-p (:position (k/constant-velocity {:position p0 :velocity v0} dt)) 1e-6))))

  (testing "3D motion"
    (let [p0 [1.0 2.0 3.0]
          v0 [1.0 -1.0 0.5]
          dt 3.0
          expected-p [4.0 -1.0 4.5]]
      (is (v≈ expected-p (:position (k/constant-velocity {:position p0 :velocity v0} dt)) 1e-6))
      (is (v≈ v0 (:velocity (k/constant-velocity {:position p0 :velocity v0} dt)) 1e-6)))))

(deftest constant-acceleration-test
  (testing "Velocity update"
    (let [v0 [0.0 0.0 0.0]
          a  [2.0 0.0 0.0]
          dt 1.0
          expected-v [2.0 0.0 0.0]]
      (is (v≈ expected-v (:velocity (k/constant-acceleration {:position [0 0 0] :velocity v0} a dt)) 1e-6))))

  (testing "Position update (p = p0 + v0*t + 0.5*a*t^2)"
    (let [p0 [0.0 0.0 0.0]
          v0 [10.0 0.0 0.0]
          a  [-2.0 0.0 0.0]
          dt 1.0
          expected-p [9.0 0.0 0.0]
          expected-v [8.0 0.0 0.0]]
      (let [result (k/constant-acceleration {:position p0 :velocity v0} a dt)]
        (is (v≈ expected-p (:position result) 1e-6))
        (is (v≈ expected-v (:velocity result) 1e-6))))))

(deftest survival-mode-test
  (testing "Graceful degradation on bad data (No Exceptions)"
    ;; Case 1: Corrupt acceleration -> Fallback to CV
    (let [state {:position [0 0 0] :velocity [10 0 0] :acceleration [Double/NaN 0 0]}
          result (k/propagate state 2.0)]
      (is (v≈ [20.0 0.0 0.0] (:position result) 1e-6))
      (is (not (:status result)))) ;; status might be clean or degraded, but result is valid

    ;; Case 2: Corrupt velocity -> Fallback to Static (Identity)
    (let [state {:position [5 5 5] :velocity [Double/NaN 0 0]}
          result (k/propagate state 2.0)]
      (is (v≈ [5.0 5.0 5.0] (:position result) 1e-6)))

    ;; Case 3: Schema violation (missing keys) -> Return input
    (let [bad-state {:pos [1 2]}
          result (k/propagate bad-state 1.0)]
      (is (= bad-state result))))

  (testing "Latency Clamping"
    ;; Case 1: Future timestamp (negative dt) -> Clamp to 0 (No move)
    (let [state {:position [0 0 0] :velocity [10 0 0]}
          cmd-time 105.0
          now-time 100.0 ;; 5 seconds in past?? Future command.
          result (k/compensate-latency state cmd-time now-time)]
      (is (v≈ [0.0 0.0 0.0] (:position result) 1e-6)))

    ;; Case 2: Ancient timestamp (stale) -> Clamp to MAX_LATENCY (e.g., 2.0s)
    (let [state {:position [0 0 0] :velocity [10 0 0]}
          cmd-time 0.0
          now-time 100.0 ;; 100s latency
          result (k/compensate-latency state cmd-time now-time)]
      ;; Should not project 1000m. Should clamp to ~2.0s -> 20m
      (is (v≈ [20.0 0.0 0.0] (:position result) 1e-6))))

  (testing "Acceleration Decay (The Ghost Target Fix)"
    (let [state {:position [0 0 0] :velocity [0 0 0] :acceleration [10 0 0]}
          dt-short 0.1
          dt-long  5.0]
      ;; Short dt: full quadratic effect p = 0.5 * 10 * 0.1^2 = 0.05
      (is (v≈ [0.05 0.0 0.0] (:position (k/propagate state dt-short)) 1e-6))
      
      ;; Long dt: If naive, p = 0.5 * 10 * 25 = 125.
      ;; With decay/clamping, it should be significantly less.
      (let [p-long (:position (k/propagate state dt-long))]
        (is (< (nth p-long 0) 125.0))))))

(deftest risk-awareness-test
  (testing "Propagates uncertainty automatically"
    (let [state {:position [0 0 0] :velocity [10 0 0]
                 :uncertainty {:pos 10.0 :vel 1.0 :acc-unknown 2.0}}
          dt 2.0
          result (k/propagate state dt)]
      ;; Nominal position: 0 + 10*2 = 20
      (is (v≈ [20.0 0.0 0.0] (:position result) 1e-6))
      ;; Uncertainty: 10 + 1*2 + 0.5*2*4 = 10 + 2 + 4 = 16.0
      (is (= 16.0 (get-in result [:uncertainty :pos]))))))