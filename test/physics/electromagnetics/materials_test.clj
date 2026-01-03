(ns physics.electromagnetics.materials-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.electromagnetics.materials :as materials]
            [physics.electromagnetics.math :as emath]))

(def ^:private tol 1e-6)

(defn- approx= [a b]
  (<= (Math/abs (- a b)) tol))

(deftest vacuum-constants
  (is (approx= 8.854187817e-12 materials/epsilon0))
  (is (approx= 1.2566370614e-6 materials/mu0))
  (is (approx= 299792458.0 materials/c0)))

(deftest material-normalisation
  (let [m (materials/->material {:name "metasurface-tile"
                                 :type :metasurface
                                 :permittivity-relative 2.4
                                 :permeability-relative 1.1
                                 :conductivity 150.0
                                 :loss-tangent 0.02
                                 :tunable {:bias-range [0.0 5.0]
                                           :permittivity-range [2.0 12.0]
                                           :permeability-range [1.0 1.4]}})]
    (is (= :metasurface (:material/type m)))
    (is (= [0.0 5.0] (get-in m [:material/tunable :bias-range])))
    (is (nil? (:material/sigma-distribution m)))
    (let [bias 0.5
          norm (/ (- bias 0.0) 5.0)
          expected-ε (* materials/epsilon0 (+ 2.0 (* 10.0 norm)))
          expected-μ (* materials/mu0 (+ 1.0 (* 0.4 norm)))]
      (let [perm (materials/absolute-permittivity m {:bias bias})
            mu (materials/absolute-permeability m {:bias bias})]
        (is (approx= expected-ε (:re perm)))
        (is (approx= 0.0 (:im perm)))
        (is (approx= expected-μ mu))))))

(deftest impedance-freespace
  (let [eta (materials/intrinsic-impedance materials/vacuum {:frequency-hz 1.0e9})]
    (is (approx= 376.730313461 (:re eta)))
    (is (approx= 0.0 (:im eta)))))

(deftest distribution-metadata
  (let [mat (materials/->material {:name "noisy"
                                   :conductivity {:type :uniform :min 0.1 :max 0.2}
                                   :permittivity-relative {:type :gaussian :mean 3.0 :sd 0.1}})]
    (is (= :uniform (get-in mat [:material/sigma-distribution :type])))
    (is (= :gaussian (get-in mat [:material/permittivity-distribution :type])))))

(deftest impedance-zero-frequency-lossy
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Intrinsic impedance undefined"
       (materials/intrinsic-impedance
        (materials/->material {:name "lossy"
                               :type :dielectric
                               :conductivity 0.5})
        {:frequency-hz 0.0}))))

(deftest bias-normalisation-calls-hook
  (let [flag (atom nil)]
    (materials/relative-permittivity
     (materials/->material {:name "tunable"
                            :tunable {:bias-range [0.0 1.0]
                                      :permittivity-range [2.0 4.0]}})
     {:bias -1.0
      :on-clamp #(reset! flag %)})
    (is (= {:bias -1.0 :range [0.0 1.0] :normalised 0.0} @flag))))
