(ns physics.frames-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.frames :as frames]
            [physics.spatial.frame :as frame]
            [physics.spatial.pose :as pose]))

(deftest geodetic->enu
  (let [origin (pose/->pose {:position [37.7749 -122.4194 0.0]
                             :frame :wgs84})
        target {:lat-deg 37.7750 :lon-deg -122.4190 :alt-m 120.0}
        result (frames/geodetic->enu origin target)]
    (is (= :enu (:frame result)))
    (is (<= 0.0 (first (:position result))))
    (is (<= 0.0 (nth (:position result) 2)))
    (is (<= (Math/abs (- 120.0 (nth (:position result) 2))) 0.2))))

(deftest earth-rotation-rate
  (is (pos? (frames/earth-rotation-rate))))
