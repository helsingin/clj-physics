(ns physics.cfd.core-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [physics.cfd.core :as core]))

(deftest geometry-validation-test
  (testing "valid geometry passes"
    (is (= (:id (core/validate-geometry! core/fixture-urban-canyon-geometry))
           :urban-canyon-simplified)))
  (testing "2D geometry cannot contain z extent"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"2D geometries must omit :lz-m"
         (core/validate-geometry!
          (-> core/fixture-urban-canyon-geometry
              (assoc :dimensions 2)
              (update :origin dissoc :z)
              (assoc-in [:extent :lz-m] 5.0))))))
  (testing "2D geometry cannot retain z origin"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"2D geometries must omit :z"
         (core/validate-geometry!
          (-> core/fixture-urban-canyon-geometry
              (assoc :dimensions 2)
              (update :extent dissoc :lz-m)))))))

(deftest environment-validation-test
  (testing "ISA-like atmosphere valid"
    (is (= (-> core/fixture-neutral-atmosphere core/validate-environment! :fluid)
           :air)))
  (testing "negative density rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid environment"
         (core/validate-environment!
          (assoc-in core/fixture-neutral-atmosphere
                    [:properties :density-kg-per-m3] -1.0))))))

(deftest platform-state-vector-test
  (is (= (core/platform-state-vector core/fixture-quadrotor-platform)
         [6.2 0.45 0.20])))

(deftest geometry-dof-test
  (let [cart-geom (-> core/fixture-urban-canyon-geometry
                      (assoc :type :cartesian-grid
                             :resolution {:nx 5 :ny 7 :nz 3}
                             :spacing {:dx-m 1.0 :dy-m 1.0 :dz-m 1.0}))]
    (is (= 105 (core/geometry-dof cart-geom))))
  (testing "surface mesh dof equals face count"
    (is (= 5 (core/geometry-dof core/fixture-urban-canyon-geometry)))))

(deftest reference-fluid-speed-test
  (let [speed (core/reference-fluid-speed core/fixture-neutral-atmosphere)]
    (is (<= 0 speed))
    (is (<= speed 1300))))
