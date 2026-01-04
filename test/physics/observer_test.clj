(ns physics.observer-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.observer :as observer]
            [physics.dynamics :as dyn]
            [physics.integrators :as integ]))

(deftest forward-projection
  (let [model (dyn/fetch-model :asset/fixed-wing)
        state {:position [0 0 1000]
               :velocity [70 0 0]
               :attitude {:roll 0 :pitch 0.05 :yaw 0}}
        controls {:throttle 0.55 :elevator 0.02 :aileron 0.0 :rudder 0.0}
        future (observer/project model state controls {:horizon 5.0 :dt 0.01})]
    (is (= 6 (count (:trajectory future))))
    (is (> (get-in future [:trajectory 5 :position 0]) 10.0))))

(deftest telemetry-synthesis
  (let [model (dyn/fetch-model :asset/ugv)
        state {:position [0 0 0]
               :velocity [10 0 0]}
        telemetry (observer/synthesise-telemetry model state {:rate 10.0 :duration 1.0})]
    (is (= 11 (count telemetry)))
    (is (every? #(contains? % :timestamp) telemetry))))
