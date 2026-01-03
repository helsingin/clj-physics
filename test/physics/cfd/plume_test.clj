(ns physics.cfd.plume-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [physics.cfd.core :as core]
   [physics.cfd.plume :as plume]))

(deftest default-geometry-test
  (let [geom (plume/default-geometry)]
    (testing "cartesian 3D grid"
      (is (= (:type geom) :cartesian-grid))
      (is (= (:dimensions geom) 3))
      (is (= (get-in geom [:resolution :nz]) 24)))))

(deftest plume-predict-structure-test
  (let [{:keys [flow-field residuals confidence]}
        (plume/predict {:parameters {:emission-rate 1.5
                                     :stability 0.6}})]
    (testing "scalar field present"
      (is (seq (get-in flow-field [:scalar]))))
    (testing "residual metadata"
      (is (map? residuals)))
    (testing "confidence within bounds"
      (is (<= 0.0 confidence 1.0)))))
