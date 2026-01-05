(ns physics.observer-ekf-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.observer.ekf :as ekf]
            [physics.math.linear :as lin]
            [physics.observer :as obs]
            [physics.models.common :as models]))

(deftest ekf-prediction-step
  (let [model models/fixed-wing
        state {:position [0 0 1000]
               :velocity [100 0 0]
               :orientation [1 0 0 0]
               :angular-rate [0 0 0]}
        controls {:throttle 0.5}
        n 13
        P (lin/identity-mat n)
        Q (lin/scalar-mul (lin/identity-mat n) 0.01)
        ;; Run one predict step
        result (ekf/predict {:state-est state
                             :covariance P
                             :model model
                             :controls controls
                             :dt 0.1
                             :Q Q})]
    (testing "State is propagated"
      (let [new-pos (:position (:state-est result))]
        ;; Should move ~10m in X (100 m/s * 0.1s)
        (is (> (first new-pos) 9.0))
        (is (< (first new-pos) 11.0))))
    
    (testing "Covariance grows due to process noise"
      (let [new-P (:covariance result)]
        ;; P_pred = Phi P Phi^T + Q
        ;; Phi approx I. So P approx I + Q.
        ;; Diagonal elements should be > 1.0
        (is (> (get-in new-P [0 0]) 1.0))))))

(deftest ekf-update-step
  (let [state {:position [10 0 0]
               :velocity [0 0 0]
               :orientation [1 0 0 0]
               :angular-rate [0 0 0]}
        n 13
        P (lin/scalar-mul (lin/identity-mat n) 100.0) ;; High uncertainty
        ;; Measure Position X only
        H (assoc (lin/zeros 1 n) 0 (assoc (vec (repeat n 0.0)) 0 1.0)) ;; [[1 0 ...]]
        z [12.0] ;; Measure X=12
        R [[1.0]] ;; Low measurement noise
        
        result (ekf/update-step {:state-est state :covariance P} H z R)
        new-x (first (:position (:state-est result)))]
    
    (testing "Measurement corrects state"
      ;; Prior X=10 (uncertainty 100). Measurement X=12 (uncertainty 1).
      ;; Posterior should be very close to 12.
      (is (> new-x 11.5))
      (is (< new-x 12.1)))))
