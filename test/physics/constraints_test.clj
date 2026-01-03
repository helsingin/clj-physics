(ns physics.constraints-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.constraints :as constraints]
            [physics.dynamics :as dyn]))

(deftest envelope-violations
  (let [model (dyn/fetch-model :asset/fixed-wing)
        flight {:mach 0.95 :load-factor 9.0 :bank 70.0 :aoa 25.0}
        violations (constraints/evaluate-envelope model flight)]
    (is (seq violations))
    (is (some #(= (:type %) :stall) violations))
    (is (some #(= (:type %) :over-g) violations))))

(deftest maritime-depth-limit
  (let [model (dyn/fetch-model :asset/submarine)
        state {:depth 600.0}
        violations (constraints/evaluate-depth model state)]
    (is (seq violations))
    (is (= :over-depth (:type (first violations))))))
