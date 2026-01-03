(ns physics.electromagnetics.constraints-test
  (:require [clojure.test :refer [deftest is]]
            [physics.electromagnetics.constraints :as constraints]
            [physics.electromagnetics.fields :as fields]
            [physics.electromagnetics.materials :as materials]))

(deftest flags-violations-when-threshold-exceeded
  (let [field (fields/->field {:type :electric
                               :frequency-hz 2.4e9
                               :amplitude 120.0})
        density-violations (constraints/evaluate-power-density field materials/vacuum {:max-w-per-m2 5.0})
        e-field-violations (constraints/evaluate-field-amplitude field {:limit 60.0})]
    (is (= 1 (count density-violations)))
    (let [violation (first density-violations)]
      (is (= {:constraint :field/power-density} (:type violation)))
      (is (= :critical (:severity violation)))
      (is (= :electric (get-in violation [:field :field/type]))))
    (is (= 1 (count e-field-violations)))))

(deftest passes-when-below-limits
  (let [field (fields/->field {:type :electric
                               :frequency-hz 2.4e9
                               :amplitude 10.0})
        density-violations (constraints/evaluate-power-density field materials/vacuum {:max-w-per-m2 10.0})
        e-field-violations (constraints/evaluate-field-amplitude field {:limit 20.0})]
    (is (empty? density-violations))
    (is (empty? e-field-violations))))
