(ns physics.electromagnetics.fields-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.electromagnetics.fields :as fields]
            [physics.electromagnetics.materials :as materials]
            [physics.electromagnetics.math :as emath]))

(def ^:private tolerance 1e-9)

(defn- approx= [a b]
  (<= (Math/abs (- a b)) tolerance))

(deftest field-normalisation
  (let [field (fields/->field {:type :electric
                               :frequency-hz 1.0e9
                               :amplitude 25.0
                               :orientation [0.0 0.0 3.0]
                               :phase-deg 370.0})
        orientation (:field/orientation field)]
    (is (= :electric (:field/type field)))
    (is (approx= 1.0 (nth orientation 2)))
    (is (approx= 0.0 (nth orientation 0)))
    (is (approx= 0.0 (nth orientation 1)))
    (is (= 10.0 (:field/phase-deg field)))
    (is (approx= (* 20.0 (Math/log10 25.0)) (:field/amplitude-db field)))))

(deftest superposition-aligns-phase
  (let [f1 (fields/->field {:type :electric
                            :frequency-hz 5.0e9
                            :amplitude 10.0
                            :orientation [1 0 0]
                            :phase-deg 0.0
                            :meta {:id 1}})
        f2 (fields/->field {:type :electric
                            :frequency-hz 5.0e9
                            :amplitude 10.0
                            :orientation [1 0 0]
                            :phase-deg 90.0
                            :meta {:id 2}})
        result (fields/superpose [f1 f2])]
    (is (approx= 14.1421356237 (:field/amplitude result)))
    (is (approx= 45.0 (:field/phase-deg result)))
    (is (approx= 1.0 (first (:field/orientation result))))
    (is (approx= 0.0 (nth (:field/orientation result) 2)))
    (is (= [{:id 1} {:id 2}] (:field/meta result)))))

(deftest power-density-matches-free-space
  (let [field (fields/->field {:type :electric
                               :frequency-hz 9.5e8
                               :amplitude 10.0
                               :orientation [0 1 0]})
        vacuum materials/vacuum
        eta (materials/intrinsic-impedance vacuum {:frequency-hz (:field/frequency-hz field)})
        expected (* 0.5
                    (:field/amplitude field)
                    (:field/amplitude field)
                    (/ (:re eta) (emath/magnitude-squared eta)))
        pd (fields/power-density field vacuum)]
    (is (true? (:valid? pd)))
    (is (approx= expected (:value pd)))))

(deftest superposition-rejects-frequency-mismatch
  (let [f1 (fields/->field {:type :electric
                            :frequency-hz 1.0e9
                            :amplitude 1.0})
        f2 (fields/->field {:type :electric
                            :frequency-hz 2.0e9
                            :amplitude 1.0})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Field frequencies must match"
         (fields/superpose [f1 f2]))))
  (let [f1 (fields/->field {:type :electric
                            :frequency-hz 1.0e9
                            :amplitude 1.0
                            :polarization :linear})
        f2 (fields/->field {:type :electric
                            :frequency-hz 1.0e9
                            :amplitude 1.0
                            :polarization :circular})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Field polarizations must match"
         (fields/superpose [f1 f2])))))

(deftest sampling-respects-distribution
  (let [field (fields/->field {:type :electric
                               :frequency-hz 1.0e9
                               :amplitude {:type :gaussian :mean 5.0 :sd 0.5}
                               :phase-deg {:type :uniform :min 0.0 :max 360.0}})
        sampled (fields/sample-field field (java.util.Random. 42))]
    (is (= (:field/type field) (:field/type sampled)))
    (is (not= (:field/amplitude field) (:field/amplitude sampled)))
    (is (:field/source sampled))))
