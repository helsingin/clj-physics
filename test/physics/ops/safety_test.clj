(ns physics.ops.safety-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.ops.safety :as safety]
            [physics.ops.kinematics :as k]))

(deftest dynamic-limits-test
  (testing "Speed Limits"
    (let [limits {:max-speed 100.0}]
      (is (true? (safety/valid-speed? [10 0 0] limits)))
      (is (false? (safety/valid-speed? [101 0 0] limits)))
      (is (true? (safety/valid-speed? [0 0 0] limits)))))

  (testing "Turn Rate Limits (rad/s)"
    (let [limits {:max-turn-rate 0.5} ;; rad/s
          state-ok  {:angular-velocity [0 0 0.4]}
          state-bad {:angular-velocity [0 0 0.6]}]
      (is (true? (safety/valid-turn-rate? state-ok limits)))
      (is (false? (safety/valid-turn-rate? state-bad limits)))))

  (testing "Combined Dynamic Check"
    (let [limits {:max-speed 10.0 :max-turn-rate 1.0}
          state-ok {:velocity [5 0 0] :angular-velocity [0 0 0.5]}
          state-bad-speed {:velocity [20 0 0] :angular-velocity [0 0 0.5]}
          state-bad-turn {:velocity [5 0 0] :angular-velocity [0 0 2.0]}]
      (is (empty? (safety/check-dynamics state-ok limits)))
      (is (contains? (safety/check-dynamics state-bad-speed limits) :speed-limit))
      (is (contains? (safety/check-dynamics state-bad-turn limits) :turn-limit)))))

(deftest separation-check-test
  (testing "Squared distance efficiency"
    (let [p1 [0 0 0]
          p2 [10 0 0]
          min-sep 5.0]
      (is (true? (safety/safe-separation? p1 p2 min-sep)))
      (is (false? (safety/safe-separation? p1 p2 15.0)))))

  (testing "Robustness (NaN inputs)"
    (is (false? (safety/safe-separation? [Double/NaN 0 0] [0 0 0] 10.0)))
    (is (false? (safety/safe-separation? [0 0 0] [0 0 0] Double/NaN)))))

(deftest boundary-check-test
  (testing "Point in Polygon (2D on XY plane)"
    (let [polygon [[0 0] [10 0] [10 10] [0 10]] ;; 10x10 square at origin
          inside [5 5 0]
          outside [15 5 0]
          almost-edge [9.99 5 0]]
      (is (true? (safety/inside-polygon-2d? inside polygon)))
      (is (false? (safety/inside-polygon-2d? outside polygon)))
      (is (true? (safety/inside-polygon-2d? almost-edge polygon)))))

  (testing "Prism Check (Altitude)"
    (let [polygon [[0 0] [10 0] [10 10] [0 10]]
          constraints {:min-alt 100.0 :max-alt 200.0}
          low  [5 5 50]
          high [5 5 250]
          good [5 5 150]]
      (is (false? (safety/inside-prism? low polygon constraints)))
      (is (false? (safety/inside-prism? high polygon constraints)))
      (is (true? (safety/inside-prism? good polygon constraints)))))

  (testing "Robustness"
    (is (false? (safety/inside-polygon-2d? [Double/NaN 0 0] [[0 0] [10 0] [0 10]])))))

(deftest predictive-safety-test
  (testing "Predictive Collision Check"
    (let [state-a {:position [0 0 0] :velocity [10 0 0]}   ;; Moving right
          state-b {:position [100 0 0] :velocity [-10 0 0]} ;; Moving left
          min-sep 5.0
          ;; Impact at t=5s (pos 50).
          
          ;; Check 0 to 4s (Safe)
          safe-scan (safety/predictive-safety? 
                      state-a state-b 
                      {:min-separation min-sep} 
                      {:horizon 4.0 :dt 1.0})
          
          ;; Check 0 to 6s (Collision at 5s)
          unsafe-scan (safety/predictive-safety? 
                        state-a state-b 
                        {:min-separation min-sep} 
                        {:horizon 6.0 :dt 1.0})]
      
      (is (true? (:safe? safe-scan)))
      (is (false? (:safe? unsafe-scan)))
      (is (= :separation-violation (:violation unsafe-scan)))
      (is (= 5.0 (:time-of-violation unsafe-scan)))))

  (testing "Predictive Boundary Exit"
    (let [state {:position [0 0 0] :velocity [10 0 0]}
          polygon [[-10 -10] [15 -10] [15 10] [-10 10]] ;; x max is 15
          ;; Will exit at t=1.5s
          
          res (safety/predictive-boundary? 
                state 
                {:polygon polygon} 
                {:horizon 3.0 :dt 0.5})]
      
      (is (false? (:safe? res)))
      (is (= :boundary-exit (:violation res)))
      (is (>= (:time-of-violation res) 1.5))))

(deftest conservative-safety-test
  (testing "Uncertainty Bubble Collision"
    ;; A and B pass with 10m separation (Nominally Safe if min-sep=5m)
    ;; But A has large uncertainty -> Bubble grows to overlap B.
    (let [state-a {:position [0 0 0] :velocity [10 0 0]}
          state-b {:position [100 10 0] :velocity [-10 0 0]} ;; y=10 offset
          
          unc-a {:pos 0.0 :vel 0.0 :acc-unknown 2.0} ;; Grows by t^2
          unc-b {:pos 0.0 :vel 0.0 :acc-unknown 0.0}
          
          opts {:horizon 5.0 :dt 1.0}
          constraints {:min-separation 5.0}]

      ;; 1. Check Nominal (Optimistic) -> Should be Safe
      ;; Closest approach at t=5: pos A=[50 0 0], B=[50 10 0]. Dist=10. > 5.
      (is (true? (:safe? (safety/predictive-safety? state-a state-b constraints opts))))

      ;; 2. Check Conservative -> Should Fail
      ;; At t=5, Err_A = 0.5 * 2 * 25 = 25m.
      ;; Required Sep = 5 + 25 + 0 = 30m.
      ;; Actual Dist = 10m.
      ;; 10 < 30 -> Violation.
      (let [res (safety/conservative-predictive-safety? 
                  state-a state-b unc-a unc-b constraints opts)]
        (is (false? (:safe? res)))
        (is (= :uncertainty-violation (:violation res)))
        (is (<= (:time-of-violation res) 5.0)))))

(deftest conservative-boundary-test
  (testing "Bubble Breach of Perimeter"
    (let [state {:position [0 0 0] :velocity [10 0 0]}
          polygon [[-20 -20] [50 -20] [50 20] [-20 20]] ;; x-max is 50
          ;; At t=4.0, pos=[40 0 0]. Nominal is safe.
          ;; But uncertainty grows.
          uncertainty {:pos 0.0 :vel 0.0 :acc-unknown 2.0} ;; E = 0.5 * 2 * t^2
          ;; At t=4.0, E = 16.0.
          ;; 40 + 16 = 56. 56 > 50. BREACH.
          
          res (safety/conservative-predictive-boundary? 
                state uncertainty polygon {} {:horizon 5.0 :dt 1.0})]
      
      (is (false? (:safe? res)))
      (is (= :conservative-boundary-violation (:violation res)))
      (is (<= (:time-of-violation res) 4.0))))

  (testing "Buffer Inflation (Static Margin) Respect"
    (let [state {:position [9 5 0] :velocity [0 0 0]} ;; Static at x=9
          polygon [[0 0] [10 0] [10 10] [0 10]]       ;; Edge at x=10
          uncertainty {:pos 0.5}                      ;; Error 0.5m -> Reach 9.5m. SAFE.
          
          ;; But we inflate polygon by 1.0m margin.
          ;; Effective boundary becomes x=9.0 (internal buffer).
          ;; 9.5 reaches past 9.0 buffer zone?
          ;; distance to edge is 1.0.
          ;; effective radius = 0.5 (unc) + 1.0 (margin) = 1.5.
          ;; 1.0 < 1.5 -> FAIL.
          
          res (safety/conservative-predictive-boundary?
                state uncertainty polygon {:margin 1.0} {:horizon 1.0 :dt 1.0})]
      
      (is (false? (:safe? res)))
      (is (= :conservative-boundary-violation (:violation res))))))))
