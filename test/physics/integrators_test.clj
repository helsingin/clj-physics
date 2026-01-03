(ns physics.integrators-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.integrators :as integ]))

(defn approx= [a b tol]
  (<= (Math/abs (- a b)) tol))

(defn harmonic-oscillator [t [x v]]
  [v (- x)])

(deftest rk4-stability
  (let [result (integ/rk4 {:derivative harmonic-oscillator
                           :initial-state [1.0 0.0]
                           :t0 0.0
                           :dt 0.01
                           :steps 628})]
    (is (approx= 1.0 (first (:state result)) 1e-2))))

(deftest adaptive-rkf45
  (let [result (integ/rkf45 {:derivative harmonic-oscillator
                             :initial-state [1.0 0.0]
                             :t-span [0.0 6.28]
                             :rtol 1e-6
                             :atol 1e-6})]
    (is (approx= 0.0 (first (:state result)) 1e-3))
    (is (< 50 (count (:steps result))))))
