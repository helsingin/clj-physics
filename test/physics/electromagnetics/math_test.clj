(ns physics.electromagnetics.math-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.electromagnetics.math :as emath]))

(def ^:private tol 1e-9)

(defn- approx= [a b]
  (<= (Math/abs (- a b)) tol))

(deftest complex-division-stability
  (let [a (emath/complex 1.0e12 1.0)
        b (emath/complex 1.0e-12 1.0)
        result (emath/cdiv a b)
        back (emath/c* result b)]
    (is (approx= (:re back) (:re a)))
    (is (approx= (:im back) (:im a)))))

(deftest complex-sqrt-edge-cases
  (doseq [z [(emath/complex 1.0 0.0)
             (emath/complex -1.0 0.0)
             (emath/complex 0.0 1.0)
             (emath/complex 0.0 -1.0)]]
    (let [s (emath/csqrt z)
          squared (emath/c* s s)
          err (emath/magnitude (emath/c- squared z))]
      (is (<= err 1e-9)))))

(deftest magnitude-order-and-flags
  (let [z (emath/complex 1e-6 0.0)]
    (is (approx= (:mag-order z) -6.0))
    (is (:valid? z)))
  (let [z (emath/complex Double/NaN 0.0)]
    (is (false? (:valid? z)))
    (is (contains? (:flags z) :non-finite))))

(deftest high-precision-multiplication-flags
  (binding [emath/*scale-threshold* 5.0
            emath/*validity-log* (atom [])]
    (let [a (emath/complex 1e5 0.0)
          b (emath/complex 1e5 0.0)
          result (emath/c* a b)]
      (is (contains? (:flags result) :scale-extreme))
      (is (:valid? result))
      (is (some #(= :mul (:op %)) @emath/*validity-log*)))))

(deftest division-condition-flag
  (binding [emath/*condition-threshold* 10.0]
    (let [a (emath/complex 1.0 0.0)
          b (emath/complex 1e-9 1e-12)
          result (emath/cdiv a b)]
      (is (contains? (:flags result) :condition-high)))))

(deftest invalid-input-propagation
  (let [bad (emath/complex Double/NaN 0.0)
        good (emath/complex 1.0 1.0)
        result (emath/c+ bad good)]
    (is (contains? (:flags result) :invalid-input))
    (is (false? (:valid? result)))))

(deftest diagnostics-capture
  (let [log (atom [])]
    (binding [emath/*validity-log* log]
      (emath/cdiv (emath/complex 1.0 0.0)
                  (emath/complex 0.0 0.0)))
    (is (seq @log))
    (is (= :div (:op (last @log))))))
