(ns physics.electromagnetics.propagation-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.electromagnetics.fields :as fields]
            [physics.electromagnetics.materials :as materials]
            [physics.electromagnetics.propagation :as propagation]
            [physics.electromagnetics.math :as emath]))

(def ^:private tol 1e-6)

(defn- approx= [a b]
  (<= (Math/abs (- a b)) tol))

(deftest attenuation-and-phase-in-lossless-medium
  (let [material materials/vacuum
        freq 1.0e9
        alpha (propagation/attenuation-constant material freq)
        beta (propagation/phase-constant material freq)
        expected-beta (/ (* 2.0 Math/PI freq) materials/c0)]
    (is (approx= 0.0 alpha))
    (is (approx= expected-beta beta)))
  (let [field (fields/->field {:type :electric
                               :frequency-hz 1.0e9
                               :amplitude 5.0
                               :phase-deg 0.0
                               :orientation [0 0 1]})
        distance 10.0
        expected-beta (/ (* 2.0 Math/PI (:field/frequency-hz field)) materials/c0)
        expected-phase-deg (mod (* expected-beta distance (/ 180.0 Math/PI)) 360.0)
        {:keys [field metrics]} (propagation/propagate-plane-wave field materials/vacuum distance)]
    (is (approx= 5.0 (:field/amplitude field)))
    (is (approx= expected-phase-deg (:field/phase-deg field)))
    (is (approx= 0.0 (get metrics :attenuation-db)))
    (is (approx= 0.0 (get metrics :attenuation-db-per-m)))
    (is (approx= (* expected-beta distance (/ 180.0 Math/PI))
                 (get metrics :phase-shift-deg)))
    (is (true? (get-in metrics [:health :valid?])))
    (is (approx= (/ (* 2.0 Math/PI (:field/frequency-hz field)) materials/c0)
                 (:gamma-magnitude (get-in metrics [:health])))
        "Gamma magnitude reported")))

(deftest conductive-medium-attenuates
  (let [material (materials/->material {:type :dielectric
                                        :permittivity-rel 80.0
                                        :permeability-rel 1.0
                                        :conductivity-s-m 4.0})
        freq 1.0e6
        alpha (propagation/attenuation-constant material freq)
        beta (propagation/phase-constant material freq)
        ε (materials/absolute-permittivity material)
        μ (materials/absolute-permeability material)
        σ (:material/conductivity-s-m material)
        ω (* 2.0 Math/PI freq)
        ωc (emath/complex ω 0.0)
        μc (emath/complex μ 0.0)
        gamma (emath/csqrt (emath/c* (emath/c+ (emath/complex σ 0.0)
                                               (emath/c* emath/j (emath/c* ωc ε)))
                                      (emath/c* emath/j (emath/c* ωc μc))))
        expected-alpha (:re gamma)
        expected-beta (:im gamma)]
    (is (approx= expected-alpha alpha))
    (is (approx= expected-beta beta))
    (let [distance 1.0
          field (fields/->field {:type :electric
                                 :frequency-hz freq
                                 :amplitude 1.0
                                 :phase-deg 0.0})
          {:keys [field metrics]} (propagation/propagate-plane-wave field material distance)
          expected-phase (mod (* beta distance (/ 180.0 Math/PI)) 360.0)
          expected-amplitude (Math/exp (* -1.0 alpha distance))]
      (is (approx= expected-amplitude (:field/amplitude field)))
      (is (approx= expected-phase (:field/phase-deg field)))
      (is (approx= (* 8.686 alpha) (get metrics :attenuation-db-per-m)))
      (is (true? (get-in metrics [:health :valid?])))))) 

(deftest propagation-zero-frequency-throws
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Propagation constant undefined"
       (propagation/propagate-plane-wave
        (fields/->field {:type :electric
                         :frequency-hz 0.0
                         :amplitude 1.0})
        (materials/->material {:name "conductor" :conductivity-s-m 1.0})
        1.0))))

(deftest monte-carlo-summaries
  (let [field (fields/->field {:type :electric
                               :frequency-hz 1.0e9
                               :amplitude {:type :gaussian :mean 5.0 :sd 0.1}})
        material (materials/->material {:type :dielectric
                                        :permittivity-rel {:type :gaussian :mean 2.0 :sd 0.05}
                                        :conductivity-s-m {:type :uniform :min 0.1 :max 0.2}})
        {:keys [amplitude attenuation-db] :as result}
        (propagation/propagate-monte-carlo field material 5.0 {:samples 50 :rng (java.util.Random. 7)})]
    (is (= 50 (:samples result)))
    (is (pos? (:valid-samples result)))
    (is (< (Math/abs (- (:mean amplitude) 5.0)) 0.2))
    (is (map? attenuation-db))))
