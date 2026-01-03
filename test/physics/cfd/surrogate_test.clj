(ns physics.cfd.surrogate-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [physics.cfd.core :as core]
   [physics.cfd.surrogate :as surrogate]))

(deftest potential-flow-structure-test
  (let [geometry (-> core/fixture-urban-canyon-geometry
                     (assoc :type :cartesian-grid
                            :resolution {:nx 8 :ny 6}
                            :spacing {:dx 2.0 :dy 2.0}))
        {:keys [flow-field metadata]} (surrogate/predict {:solver :potential-flow
                                                          :geometry geometry
                                                          :parameters {:strength 4.0
                                                                       :radius 12.0}})]
    (testing "metadata present"
      (is (= (:source metadata) :analytical-potential-flow)))
    (testing "velocity field dimensions"
      (is (= (count (:velocity flow-field)) (:ny (:resolution geometry)))))))

(deftest plume-surrogate-test
  (let [geometry (-> core/fixture-urban-canyon-geometry
                     (assoc :type :cartesian-grid
                            :resolution {:nx 5 :ny 5 :nz 4}
                            :spacing {:dx 1.0 :dy 1.0 :dz 1.0}))
        {:keys [flow-field metadata]}
        (surrogate/predict {:solver :plume
                            :geometry geometry
                            :environment core/fixture-neutral-atmosphere
                            :parameters {:emission-rate 2.0
                                         :stability 0.8}})]
    (testing "scalar field dimensions"
      (is (= (count (:scalar flow-field)) 4)))
    (testing "metadata source"
      (is (= (:source metadata) :analytical-plume)))))

(deftest plume-geometry-validation-test
  (let [bad-geometry (-> core/fixture-urban-canyon-geometry
                         (assoc :dimensions 2
                                :type :cartesian-grid
                                :resolution {:nx 8 :ny 8}
                                :spacing {:dx 1.0 :dy 1.0})
                         (update :origin dissoc :z)
                         (update :extent dissoc :lz))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Plume surrogate requires 3D geometry"
         (surrogate/predict {:solver :plume
                             :geometry bad-geometry
                             :environment core/fixture-neutral-atmosphere
                             :parameters {:emission-rate 1.0}})))))

(deftest plume-environment-validation-test
  (let [geometry (-> core/fixture-urban-canyon-geometry
                     (assoc :type :cartesian-grid
                            :resolution {:nx 4 :ny 4 :nz 3}
                            :spacing {:dx 1.0 :dy 1.0 :dz 1.0}))
        bad-env (assoc-in core/fixture-neutral-atmosphere
                          [:properties :density] -1.0)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid environment"
         (surrogate/predict {:solver :plume
                             :geometry geometry
                             :environment bad-env
                             :parameters {:emission-rate 1.0}})))))

(deftest register-model-and-custom-tensor
  (let [tmp (java.nio.file.Files/createTempFile "surrogate" ".bin"
                                                (into-array java.nio.file.attribute.FileAttribute []))
        path (.toString tmp)]
    (spit path "mock weights")
    (let [entry (surrogate/register-model! {:id :mock-mgn
                                            :path path
                                            :backend :onnx
                                            :normalization {:mean 0.0 :std 1.0}
                                            :metadata {:run "test"}})
          payload {:velocity-tensor [[[{:u 0.0 :v 0.0 :w 0.0}]]]
                   :model-id :mock-mgn}
          {:keys [metadata]} (surrogate/predict {:solver :tensor
                                                 :payload payload})]
      (testing "model registry stores checksum"
        (is (= :mock-mgn (:id entry)))
        (is (string? (:checksum entry))))
      (testing "custom tensor attaches provenance"
        (is (= :mock-mgn (get-in metadata [:model :id])))))))
