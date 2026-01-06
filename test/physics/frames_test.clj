(ns physics.frames-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.frames :as frames]))

(defn- ≈ [a b tol]
  (< (Math/abs (- (double a) (double b))) tol))

(defn- v≈ [v1 v2 tol]
  (every? (fn [[x1 x2]] (≈ x1 x2 tol)) (map vector v1 v2)))

(deftest transform-roundtrip-test
  (testing "Geodetic <-> ECEF Roundtrip"
    (let [geo {:lat-deg 37.7749 :lon-deg -122.4194 :alt-m 100.0}
          ecef (frames/geodetic->ecef geo)
          back (frames/ecef->geodetic ecef)]
      (is (≈ (:lat-deg geo) (:lat-deg back) 1e-6))
      (is (≈ (:lon-deg geo) (:lon-deg back) 1e-6))
      (is (≈ (:alt-m geo) (:alt-m back) 0.01))))

  (testing "ECEF <-> ENU Roundtrip"
    (let [origin {:position [37.0 -122.0 0.0]} ;; Geodetic origin
          target-ecef [-2700000.0 -4300000.0 3800000.0]
          enu (frames/ecef->enu origin target-ecef)
          back (frames/enu->ecef origin enu)]
      (is (v≈ target-ecef back 1e-3)))))