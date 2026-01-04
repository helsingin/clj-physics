(ns physics.dynamics-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.dynamics :as dyn]
            [physics.environment :as env]))

(defn approx= [a b tol]
  (<= (Math/abs (- a b)) tol))

(deftest fixed-wing-trim
  (let [model (dyn/fetch-model :asset/fixed-wing)
        state {:position [0 0 1000]
               :velocity [70 0 0]
               :attitude {:roll 0 :pitch 0.05 :yaw 0}
               :angular-rate [0 0 0]}
        env (env/isa-profile 1000.0)
        forces (dyn/airframe-forces model state env {:throttle 0.55 :elevator 0.02})]
    (is (approx= 0.0 (nth (:net-force forces) 2) 500.0))
    (is (approx= 0.0 (nth (:net-torque forces) 1) 200.0))))

(deftest ground-vehicle-traction
  (let [model (dyn/fetch-model :asset/ugv)
        state {:velocity [15 0 0]
               :slip-angle-rad 0.1
               :terrain {:mu 0.7 :grade-rad 0.05}}
        result (dyn/ground-forces model state)]
    (is (<= (Math/abs (:lateral-force result)) (:max-lateral-force result)))))

(deftest maritime-hydrodynamics
  (let [model (dyn/fetch-model :asset/usv)
        state {:velocity [5 0 0]
               :depth-m 0.0
               :sea-state 3}
        env (env/ocean-profile {:depth-m 5.0 :lat-deg 30.0})
        forces (dyn/maritime-forces model state env {:rudder 0.1 :throttle 0.6})]
    (is (number? (:drag forces)))
    (is (number? (:lift forces)))))

(deftest orbital-two-body
  (let [earth (dyn/fetch-model :body/earth)
        orbit (dyn/orbital-derivatives earth {:position [7000000 0 0]
                                              :velocity [0 7500 0]})]
    (is (approx= 0.0 (+ (* (nth (:acceleration orbit) 0)
                            (nth (:position orbit) 0))
                         (* (nth (:velocity orbit) 0)
                            (nth (:velocity orbit) 0)))
               1e-3))))
