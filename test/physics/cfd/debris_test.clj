(ns physics.cfd.debris-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [physics.cfd.debris :as debris]))

(deftest debris-default-geometry-test
  (let [geom (debris/default-geometry)]
    (testing "2D sheet for debris transport"
      (is (= (:dimensions geom) 2))
      (is (= (:type geom) :cartesian-grid)))))

(deftest debris-predict-structure-test
  (let [{:keys [flow-field residuals confidence]}
        (debris/predict {:parameters {:impulse 15.0
                                      :roughness 0.3}})]
    (testing "velocity field present"
      (is (seq (:velocity flow-field))))
    (testing "confidence bounded"
      (is (<= 0.0 confidence 1.0)))
    (testing "residual map exists"
      (is (map? residuals)))))
