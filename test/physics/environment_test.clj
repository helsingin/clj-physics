
(ns physics.environment-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.environment :as env]))

(defn approx= [a b tol]
  (<= (Math/abs (- a b)) tol))

(deftest isa-standard-atmosphere
  (testing "sea level density"
    (let [profile (env/isa-profile 0.0)]
      (is (approx= 1.225 (:density profile) 1e-3))
      (is (approx= 101325.0 (:pressure profile) 10.0))))
  (testing "tropopause transition"
    (let [profile (env/isa-profile 11000.0)]
      (is (approx= 0.3639 (:density profile) 1e-3))
      (is (approx= -56.5 (:temperature profile) 0.5)))))

(deftest ocean-profile
  (let [profile (env/ocean-profile {:depth 200.0 :lat 34.0})]
    (is (<= (:density profile) 1050.0))
    (is (>= (:density profile) 1020.0))))
