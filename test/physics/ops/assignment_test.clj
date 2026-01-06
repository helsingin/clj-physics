(ns physics.ops.assignment-test
  (:require [clojure.test :refer [deftest is testing]]
            [physics.ops.assignment :as assign]
            [physics.ops.intercept :as int]
            [physics.core :as core]))

(defn- ≈ [a b tol]
  (< (Math/abs (- (double a) (double b))) tol))

(deftest cost-matrix-test
  (testing "Generic Cost Evaluation"
    (let [candidates [{:id 1 :fuel 10.0 :ttg 100.0}
                      {:id 2 :fuel 50.0 :ttg 20.0}]
          weights {:fuel -1.0 :ttg 2.0} ;; High fuel is good (neg cost), High TTG is bad
          ;; Cost 1: -10 + 200 = 190
          ;; Cost 2: -50 + 40 = -10
          
          costs (assign/cost-matrix candidates weights)]
      (is (≈ 190.0 (get costs 1) 1e-6))
      (is (≈ -10.0 (get costs 2) 1e-6))))

  (testing "Robustness (NaN inputs)"
    (let [candidates [{:id 1 :val Double/NaN}]
          weights {:val 1.0}
          costs (assign/cost-matrix candidates weights)]
      (is (= Double/POSITIVE_INFINITY (get costs 1)))))

  (testing "Robustness (Missing keys)"
    (let [candidates [{:id 1 :fuel 10.0}] ;; Missing :ttg
          weights {:fuel 1.0 :ttg 1.0}
          costs (assign/cost-matrix candidates weights)]
      ;; Should be Infinite, NOT 10.0
      (is (= Double/POSITIVE_INFINITY (get costs 1))))))

(deftest nearest-neighbor-test
  (testing "Squared Distance Search (Entities)"
    (let [target [0 0 0]
          agents [{:id :a :position [10 0 0]}
                  {:id :b :position [5 0 0]}]
          nearest (assign/nearest-neighbor target agents)]
      (is (= :b (:id nearest)))))

  (testing "Robustness (Bad Candidates)"
    (let [target [0 0 0]
          agents [{:id :bad} ;; No position
                  {:id :good :position [5 0 0]}]
          nearest (assign/nearest-neighbor target agents)]
      (is (= :good (:id nearest)))))
  
  (testing "Empty List"
    (is (nil? (assign/nearest-neighbor [0 0 0] [])))))

(deftest sector-assignment-test
  (testing "Sector Mapping (Entities & Vectors)"
    (let [center [0 0 0]
          sectors [{:id :north :min-az 315 :max-az 45}]
          ;; Entity
          target-n {:id :uav :position [0 10 0]}
          ;; Raw Vector
          target-v [0 5 0]
          
          assignment (assign/assign-sectors [target-n target-v] center sectors)]
      
      (is (contains? (:north assignment) target-n))
      (is (contains? (:north assignment) target-v)))))

(deftest feasibility-filtering-test
  (testing "Filter by Intercept Feasibility"
    (let [target {:position [100 0 0] :velocity [0 0 0]}
          agents [{:id :fast :position [0 0 0] :max-speed 20.0} ;; Can reach
                  {:id :slow :position [0 0 0] :max-speed 1.0}] ;; Too slow for operational timeout?
          
          ;; Simple constraint: TTG < 10.0s
          constraint-fn (fn [agent]
                          (let [sol (int/time-to-go (:position agent) (:position target) (:max-speed agent))]
                            (< (:ttg sol) 10.0)))
          
          valid (assign/filter-feasible agents constraint-fn)]
      
      (is (= 1 (count valid)))
      (is (= :fast (:id (first valid)))))))
