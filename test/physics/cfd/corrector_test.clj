(ns physics.cfd.corrector-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [physics.cfd.core :as core]
   [physics.cfd.surrogate :as surrogate]
   [physics.cfd.corrector :as corrector]))

(defn- cartesian-geometry-3d
  []
  (-> core/fixture-urban-canyon-geometry
      (assoc :type :cartesian-grid
             :dimensions 3
             :resolution {:nx 18 :ny 14 :nz 6}
             :spacing {:dx-m 1.5 :dy-m 1.5 :dz-m 1.2})))

(defn- cartesian-geometry-2d
  []
  {:id :shear-layer
   :type :cartesian-grid
   :dimensions 2
   :origin {:x -20.0 :y -12.0}
   :extent {:lx-m 40.0 :ly-m 24.0}
   :resolution {:nx 48 :ny 30}
   :spacing {:dx-m (/ 40.0 47) :dy-m (/ 24.0 29)}})

(defn- velocity-volume
  [velocity geometry]
  (if (= 2 (:dimensions geometry))
    [velocity]
    velocity))

(defn- divergence-field
  [velocity geometry]
  (let [{:keys [resolution spacing]} geometry
        {:keys [nx ny nz]} resolution
        {:keys [dx-m dy-m dz-m]} spacing
        nz* (or nz 1)
        inv2dx (/ 1.0 (* 2.0 dx-m))
        inv2dy (/ 1.0 (* 2.0 dy-m))
        inv2dz (when dz-m (/ 1.0 (* 2.0 dz-m)))
        vol (velocity-volume velocity geometry)]
    (vec
     (for [k (range nz*)]
       (vec
        (for [j (range ny)]
          (vec
           (for [i (range nx)]
             (let [cell (get-in vol [k j i] {:u 0.0 :v 0.0 :w 0.0})
                   u+ (:u (get-in vol [k j (min (dec nx) (inc i))] cell))
                   u- (:u (get-in vol [k j (max 0 (dec i))] cell))
                   v+ (:v (get-in vol [k (min (dec ny) (inc j)) i] cell))
                   v- (:v (get-in vol [k (max 0 (dec j)) i] cell))
                   w+ (if inv2dz
                        (:w (get-in vol [(min (dec nz*) (inc k)) j i] cell))
                        0.0)
                   w- (if inv2dz
                        (:w (get-in vol [(max 0 (dec k)) j i] cell))
                        0.0)]
               (+ (* inv2dx (- u+ u-))
                  (* inv2dy (- v+ v-))
                  (if inv2dz (* inv2dz (- w+ w-)) 0.0)))))))))))

(defn- interior-max-divergence
  [velocity geometry]
  (let [{:keys [resolution dimensions]} geometry
        {:keys [nx ny nz]} resolution
        nx-idx (if (> nx 2) (range 1 (dec nx)) (range nx))
        ny-idx (if (> ny 2) (range 1 (dec ny)) (range ny))
        nz* (if (= dimensions 2) 1 (or nz 1))
        nz-idx (if (> nz* 2) (range 1 (dec nz*)) (range nz*))
        div (divergence-field velocity geometry)]
    (reduce
     (fn [acc k]
       (reduce
        (fn [acc2 j]
          (reduce
           (fn [acc3 i]
             (max acc3
                  (Math/abs ^double (get-in div [k j i] 0.0))))
           acc2
           nx-idx))
        acc
        ny-idx))
     0.0
     nz-idx)))

(defn- run-corrector
  [geometry flow-field opts]
  (corrector/correct {:geometry geometry}
                     flow-field
                     opts))

(defn- resolve-private
  [sym]
  (or (ns-resolve 'physics.cfd.corrector sym)
      (throw (ex-info "Missing private var" {:sym sym}))))

(deftest flatten-roundtrips
  (let [geom (cartesian-geometry-3d)
        velocity-2d [[{:u 1.0 :v 2.0 :w 3.0} {:u -1.0 :v -2.0 :w -3.0}]]
        vol ((resolve-private 'canonical-volume) velocity-2d {:dimensions 2})
        flat-vel ((resolve-private 'flatten-velocity) vol {:dimensions 2} {:nx 2 :ny 1 :nz 1})
        roundtrip ((resolve-private 'unflatten-vector-volume)
                   (:u flat-vel) (:v flat-vel) (:w flat-vel)
                   {:dimensions 2} {:nx 2 :ny 1 :nz 1})
        scalar [[[1.0 2.0] [3.0 4.0]]]
        scalar-flat ((resolve-private 'flatten-scalar) scalar {:dimensions 2} {:nx 2 :ny 2 :nz 1})
        scalar-rt ((resolve-private 'unflatten-scalar-volume) scalar-flat {:dimensions 2} {:nx 2 :ny 2 :nz 1})]
    (testing "velocity flatten/unflatten round-trip"
      (is (= vol roundtrip)))
    (testing "scalar flatten/unflatten round-trip"
      (is (= scalar scalar-rt)))))

(deftest divergence-and-gradient-shape
  (let [geometry (cartesian-geometry-3d)
        vel ((resolve-private 'zero-vector-volume) geometry)
        div ((resolve-private 'divergence) vel geometry)
        phi (vec (repeat 6 (vec (repeat 14 (vec (repeat 18 1.0))))))
        grad ((resolve-private 'gradient-volume) phi geometry)]
    (testing "divergence shape matches geometry"
      (is (= (get-in geometry [:resolution :nz]) (count div)))
      (is (= (get-in geometry [:resolution :ny]) (count (first div))))
      (is (= (get-in geometry [:resolution :nx]) (count (ffirst div)))))
    (testing "gradient shape matches geometry"
      (is (= (get-in geometry [:resolution :nz]) (count grad)))
      (is (= (get-in geometry [:resolution :ny]) (count (first grad))))
      (is (= (get-in geometry [:resolution :nx]) (count (ffirst grad)))))))

(deftest solve-poisson-parallel-matches-serial-on-zero-divergence
  (let [geometry (cartesian-geometry-3d)
        zero-div (vec (repeat 6 (vec (repeat 14 (vec (repeat 18 0.0))))))
        solve (resolve-private 'solve-poisson)
        phi-serial (solve zero-div geometry 2 {:parallel? false})
        phi-parallel (solve zero-div geometry 2 {:parallel? true})]
    (testing "zero divergence yields identical potentials serial vs parallel"
      (is (= phi-serial phi-parallel)))))

(deftest helmholtz-projection-reduces-divergence
  (let [geometry (cartesian-geometry-3d)
        {:keys [flow-field]} (surrogate/predict {:solver :potential-flow
                                                 :geometry geometry
                                                 :parameters {:strength 7.0
                                                              :radius 8.0}})
        baseline (interior-max-divergence (:velocity flow-field) geometry)
        {:keys [flow-field residuals confidence]}
        (run-corrector geometry flow-field {:iterations 80
                                            :energy-limit 40.0})
        corrected (interior-max-divergence (:velocity flow-field) geometry)]
    (testing "divergence collapses by two orders of magnitude inside the domain"
      (is (< corrected (* baseline 1.0e-2))))
    (testing "absolute divergence tolerance holds"
      (is (< corrected 5.0e-4)))
    (testing "residual metadata reflects improvement"
      (is (< (:max-divergence residuals) baseline)))
    (testing "confidence between 0 and 1"
      (is (<= 0.0 confidence 1.0)))))

(deftest projection-supports-2d-velocity-fields
  (let [geometry (cartesian-geometry-2d)
        {:keys [flow-field]} (surrogate/predict {:solver :potential-flow
                                                 :geometry geometry
                                                 :parameters {:strength 5.0}})
        baseline (interior-max-divergence (:velocity flow-field) geometry)
        {:keys [flow-field residuals confidence]}
        (run-corrector geometry flow-field {:iterations 80
                                            :energy-limit 25.0})
        corrected (interior-max-divergence (:velocity flow-field) geometry)]
    (testing "shape remains 2D (ny-by-nx grid)"
      (let [velocity (:velocity flow-field)]
        (is (= (count velocity) (get-in geometry [:resolution :ny])))
        (is (= (count (first velocity))
               (get-in geometry [:resolution :nx])))))
    (testing "divergence shrinks significantly in 2D"
      (is (< corrected (* baseline 5.0e-2))))
    (testing "absolute divergence tolerance holds in 2D"
      (is (< corrected 1.0e-3)))
    (testing "metadata residual mirrors reduction"
      (is (< (:max-divergence residuals) baseline)))
    (testing "confidence bounded"
      (is (<= 0.0 confidence 1.0)))))

(deftest scalar-field-bypasses-corrector
  (let [geometry (cartesian-geometry-3d)
        scalar-field {:scalar [[[0.1 0.2] [0.3 0.4]]]
                      :mode :plume}
        {:keys [flow-field residuals confidence]}
        (run-corrector geometry scalar-field {:iterations 10})]
    (testing "scalar-only field passes through unchanged"
      (is (= scalar-field flow-field)))
    (testing "residuals acknowledge bypass"
      (is (= 0.0 (:max-divergence residuals))))
    (testing "confidence maximal"
      (is (= 1.0 confidence)))))
