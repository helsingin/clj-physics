(ns physics.ops.rigor-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.ops.kinematics :as k]
            [physics.ops.safety :as safety]
            [physics.ops.uncertainty :as unc]))

(defn- v≈ [v1 v2 tol]
  (every? (fn [[x1 x2]] (< (Math/abs (- x1 x2)) tol)) (map vector v1 v2)))

(deftest temporal-truth-test
  (testing "Propagate > MAX_DT iterates correctly"
    ;; Request 25s propagation. MAX_DT is 10s.
    ;; Should iterate 10 -> 10 -> 5.
    ;; v = 10. pos = 0.
    ;; Expected pos = 250.
    ;; If it clamped silently, pos would be 100.
    (let [state {:position [0 0 0] :velocity [10 0 0]}
          dt 25.0
          res (k/propagate state dt)]
      (is (v≈ [250.0 0.0 0.0] (:position res) 1e-6))
      (is (nil? (:status res))))) ;; Should not be degraded

  (testing "Uncertainty grows over iteration"
    ;; 25s propagation.
    ;; Err vel = 1.0.
    ;; Total error should be 25.0.
    ;; If clamped to 10s, error would be 10.0.
    (let [state {:position [0 0 0] :velocity [0 0 0] 
                 :uncertainty {:pos 0.0 :vel 1.0}}
          dt 25.0
          res (k/propagate state dt)]
      (is (= 25.0 (get-in res [:uncertainty :pos]))))))

(deftest ray-cast-edge-rigor-test
  (testing "Points exactly on edge or vertex"
    (let [poly [[0 0] [10 0] [10 10] [0 10]]]
      ;; Vertex
      (is (true? (safety/inside-polygon-2d? [0 0 0] poly)))
      ;; Edge
      (is (true? (safety/inside-polygon-2d? [5 0 0] poly)))
      ;; Outside (epsilon)
      (is (false? (safety/inside-polygon-2d? [10.01 5 0] poly))))))
