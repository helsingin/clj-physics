(ns physics.cfd.api-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [physics.cfd.api :as api]
   [physics.cfd.core :as core]))

(def airflow-geometry
  (-> core/fixture-urban-canyon-geometry
      (assoc :type :cartesian-grid
             :dimensions 2
             :origin {:x -30.0 :y -30.0}
             :extent {:lx 60.0 :ly 60.0}
             :resolution {:nx 32 :ny 32}
             :spacing {:dx 1.9375 :dy 1.9375})))

(deftest predict-flow-airflow-test
  (let [result (api/predict-flow {:mode :airflow
                                  :geometry airflow-geometry
                                  :parameters {:strength 4.0 :radius 10.0}
                                  :corrector-options {:iterations 60}})
        {:keys [flow-field constraints confidence metadata]} result]
    (testing "returns canonical keys"
      (is (map? flow-field))
      (is (map? constraints))
      (is (number? confidence)))
    (testing "confidence bounded and constraints include divergence info"
      (is (<= 0.0 confidence 1.0))
      (is (contains? constraints :max-divergence)))
    (testing "metadata tags airflow mode"
      (is (= (:mode metadata) :airflow)))))

(deftest predict-flow-plume-test
  (let [result (api/predict-flow {:mode :plume
                                  :parameters {:emission-rate 1.2 :stability 0.4}})
        {:keys [flow-field constraints confidence metadata]} result]
    (testing "plume provides scalar field"
      (is (seq (:scalar flow-field))))
    (testing "constraints present with finite divergence bound"
      (is (< (:max-divergence constraints) 1.0)))
    (testing "metadata tags plume mode"
      (is (= (:mode metadata) :plume)))
    (testing "confidence bounded"
      (is (<= 0.0 confidence 1.0)))))

(deftest predict-flow-invalid-mode-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unsupported CFD mode"
       (api/predict-flow {:mode :unknown}))))
