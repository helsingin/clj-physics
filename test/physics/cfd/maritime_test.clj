(ns physics.cfd.maritime-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [physics.cfd.maritime :as maritime]))

(deftest maritime-default-geometry-test
  (let [geom (maritime/default-geometry)]
    (testing "shallow 3D domain"
      (is (= (:dimensions geom) 3))
      (is (= (:type geom) :cartesian-grid))
      (is (< (get-in geom [:extent :lz]) 10.0)))))

(deftest maritime-predict-structure-test
  (let [{:keys [flow-field residuals confidence]}
        (maritime/predict {:parameters {:wave-height 1.2
                                        :wave-length 25.0
                                        :current-speed 2.0}})]
    (testing "velocity field present"
      (is (seq (:velocity flow-field))))
    (testing "residual map returned"
      (is (map? residuals)))
    (testing "confidence bounded"
      (is (<= 0.0 confidence 1.0)))))
