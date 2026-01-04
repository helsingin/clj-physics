(ns physics.dynamics-fix-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.dynamics :as dyn]
            [physics.environment :as env]
            [physics.models.common :as models]))

(deftest airframe-forces-correct-altitude-lookup
  (let [model models/fixed-wing
        ;; State at 10,000m altitude (positive Z in ENU)
        state-10k {:position [0 0 10000]
                   :velocity [100 0 0] 
                   :orientation [1 0 0 0]
                   :angular-rate [0 0 0]}
        ;; Force calculation letting the function pick the default env
        forces-default (dyn/airframe-forces model state-10k nil {})
        ;; Force calculation explicitly passing 10k environment
        env-10k (env/isa-profile 10000)
        forces-explicit (dyn/airframe-forces model state-10k env-10k {})]
    
    (testing "Default env lookup should match explicit 10km env"
      (let [lift-def (nth (:aero-force forces-default) 2)
            lift-exp (nth (:aero-force forces-explicit) 2)]
        ;; Values should be non-zero (shadowing fix)
        (is (not (zero? lift-def)))
        ;; Values should be equal (altitude fix)
        (is (<= (Math/abs (- lift-def lift-exp)) 0.001))))))
