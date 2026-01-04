(ns physics.integrators-event-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.integrators :as integ]))

(defn falling-ball [t [z v]]
  [v -9.81])

(deftest rk4-event-detection
  (testing "Detection of ground impact (z=0)"
    (let [result (integ/rk4 {:derivative falling-ball
                             :initial-state [10.0 0.0] ;; 10m high, 0 velocity
                             :t0 0.0
                             :dt 0.1
                             :steps 100
                             :events {:impact (fn [_ [z v]] z)}})
          event (:event result)]
      (is (some? event))
      (is (= :impact (:id event)))
      ;; Physics: 10 = 0.5 * 9.81 * t^2 => t = sqrt(20/9.81) approx 1.4278
      (is (<= (Math/abs (- (:time event) 1.4278)) 0.005))
      (is (<= (Math/abs (first (:state event))) 0.05)))))

(deftest rkf45-event-detection
  (testing "Adaptive detection of ground impact"
    (let [result (integ/rkf45 {:derivative falling-ball
                               :initial-state [10.0 0.0]
                               :t-span [0.0 5.0]
                               :events {:impact (fn [_ [z v]] z)}})
          event (:event result)]
      (is (some? event))
      (is (= :impact (:id event)))
      (is (<= (Math/abs (- (:time event) 1.4278)) 0.005)))))
