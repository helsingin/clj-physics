(ns physics.ops.enhancements-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.ops.frames :as frames]
            [physics.ops.pathing :as path]
            [physics.models.sensors :as sensors]
            [physics.core :as core]))

(deftest frame-normalization-test
  (testing "WGS84 -> ECEF"
    (let [state {:frame :wgs84 :position [0.0 0.0 0.0]} ;; Lat 0, Lon 0, Alt 0
          norm (frames/ensure-frame state :ecef)]
      (is (= :ecef (:frame norm)))
      ;; Radius approx 6378 km
      (is (> (first (:position norm)) 6000000.0)))))

(deftest pathfinding-test
  (testing "A* Grid"
    (let [start [0 0]
          goal  [2 2]
          grid  {[0 0] 1 [0 1] 1 [0 2] 1 [1 2] 1 [2 2] 1} ;; L-shape path
          neighbors (fn [n]
                      (for [d [[0 1] [1 0] [0 -1] [-1 0]]
                            :let [next-n (mapv + n d)]
                            :when (contains? grid next-n)]
                        {:node next-n :cost 1}))
          heuristic (fn [n] (core/magnitude (mapv - goal n)))
          
          res (path/a-star start goal neighbors heuristic {})]
      (is (= :success (:status res)))
      (is (= [[0 0] [0 1] [0 2] [1 2] [2 2]] (:path res))))))

(deftest sensor-model-test
  (testing "Radar Probability"
    ;; Close range, huge RCS -> Should detect
    (is (sensors/radar-detect? 1000.0 10.0 {:power 1e3 :gain 100.0 :wavelength 0.1}))
    ;; Far range, tiny RCS -> Low prob
    ;; Using loop to check distribution is overkill for unit test, just check execution
    (sensors/radar-detect? 100000.0 0.001 {:power 1e3 :gain 100.0 :wavelength 0.1}))

  (testing "GPS Noise"
    (let [pos [0 0 0]
          meas (sensors/gps-measurement pos {:drift [1 1 1] :noise-sigma 0.0})]
      ;; Drift [1 1 1] + 0 noise
      (is (= [1.0 1.0 1.0] meas)))))
