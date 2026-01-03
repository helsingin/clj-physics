(ns physics.cfd.corrector
  "Helmholtz–Hodge projection for surrogate flow fields.  Uses a conjugate-gradient Poisson solve
   to enforce near-zero divergence, applies boundary conditions, and limits energy so downstream
   planners receive coherent, physically plausible velocities."
  (:require
   [clojure.math :as math]
  [physics.cfd.core :as core]))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; Geometry helpers
;; -----------------------------------------------------------------------------

(defn- canonical-volume
  "Return velocity as [nz][ny][nx] structure (wrapping 2D fields into a single plane)."
  [velocity {:keys [dimensions]}]
  (when velocity
    (let [plane (fn [rows] (mapv (fn [row] (mapv identity row)) rows))]
      (if (= dimensions 2)
        [(plane velocity)]
        (mapv plane velocity)))))

(defn- restore-volume
  [volume {:keys [dimensions]}]
  (if (= dimensions 2)
    (first volume)
    volume))

(defn- scalar-volume
  [geometry]
  (let [{:keys [dimensions]} geometry
        {:keys [nx ny nz]} (:resolution geometry)
        nz* (if (= dimensions 2) 1 (or nz 1))]
    (vec (repeat nz* (vec (repeat ny (vec (repeat nx 0.0))))))))

(defn- zero-vector-volume
  [geometry]
  (let [{:keys [dimensions]} geometry
        {:keys [nx ny nz]} (:resolution geometry)
        nz* (if (= dimensions 2) 1 (or nz 1))]
    (vec
     (repeat nz*
             (vec
              (repeat ny
                      (vec
                       (repeat nx {:u 0.0 :v 0.0 :w 0.0}))))))))

(defn- interior-spec
  [geometry]
  (let [{:keys [dimensions]} geometry
        {:keys [nx ny nz]} (:resolution geometry)
        nz* (if (= dimensions 2) 1 (or nz 1))
        nx-int (max 0 (- nx 2))
        ny-int (max 0 (- ny 2))
        nz-int (if (= dimensions 2)
                 1
                 (max 0 (- nz* 2)))]
    {:dimensions dimensions
     :nx nx :ny ny :nz nz*
     :nx-int nx-int
     :ny-int ny-int
     :nz-int nz-int
     :has-interior? (and (pos? nx-int)
                         (pos? ny-int)
                         (or (= dimensions 2) (pos? nz-int)))}))

(defn- has-interior?
  [{:keys [has-interior?]}]
  has-interior?)

(defn- clamp-index [idx max-idx]
  (-> idx (max 0) (min max-idx)))

;; -----------------------------------------------------------------------------
;; Differential operators
;; -----------------------------------------------------------------------------

(defn- divergence
  [velocity geometry]
  (let [{:keys [resolution spacing dimensions]} geometry
        {:keys [nx ny nz]} resolution
        {:keys [dx dy dz]} spacing
        nz* (if (= dimensions 2) 1 (or nz 1))
        inv2dx (/ 1.0 (* 2.0 dx))
        inv2dy (/ 1.0 (* 2.0 dy))
        inv2dz (when dz (/ 1.0 (* 2.0 dz)))
        vol (canonical-volume velocity geometry)]
    (vec
     (for [k (range nz*)]
       (vec
        (for [j (range ny)]
          (vec
           (for [i (range nx)]
             (let [cell (get-in vol [k j i] {:u 0.0 :v 0.0 :w 0.0})
                   u+ (:u (get-in vol [k j (clamp-index (inc i) (dec nx))] cell))
                   u- (:u (get-in vol [k j (clamp-index (dec i) (dec nx))] cell))
                   v+ (:v (get-in vol [k (clamp-index (inc j) (dec ny)) i] cell))
                   v- (:v (get-in vol [k (clamp-index (dec j) (dec ny)) i] cell))
                   w+ (if inv2dz
                        (:w (get-in vol [(clamp-index (inc k) (dec nz*)) j i] cell))
                        0.0)
                   w- (if inv2dz
                        (:w (get-in vol [(clamp-index (dec k) (dec nz*)) j i] cell))
                        0.0)]
               (+ (* inv2dx (- u+ u-))
                  (* inv2dy (- v+ v-))
                  (if inv2dz (* inv2dz (- w+ w-)) 0.0)))))))))))

(defn- gradient-volume
  [phi geometry]
  (let [{:keys [resolution spacing dimensions]} geometry
        {:keys [nx ny nz]} resolution
        {:keys [dx dy dz]} spacing
        nz* (if (= dimensions 2) 1 (or nz 1))
        inv2dx (/ 1.0 (* 2.0 dx))
        inv2dy (/ 1.0 (* 2.0 dy))
        inv2dz (when dz (/ 1.0 (* 2.0 dz)))]
    (vec
     (for [k (range nz*)]
       (vec
        (for [j (range ny)]
          (vec
           (for [i (range nx)]
             (let [phi+ (get-in phi [k j (clamp-index (inc i) (dec nx))] 0.0)
                   phi- (get-in phi [k j (clamp-index (dec i) (dec nx))] 0.0)
                   phin+ (get-in phi [k (clamp-index (inc j) (dec ny)) i] 0.0)
                   phin- (get-in phi [k (clamp-index (dec j) (dec ny)) i] 0.0)
                   phik+ (if inv2dz
                           (get-in phi [(clamp-index (inc k) (dec nz*)) j i] 0.0)
                           0.0)
                   phik- (if inv2dz
                           (get-in phi [(clamp-index (dec k) (dec nz*)) j i] 0.0)
                           0.0)]
               {:u (* inv2dx (- phi+ phi-))
                :v (* inv2dy (- phin+ phin-))
                :w (if inv2dz (* inv2dz (- phik+ phik-)) 0.0)})))))))))

(defn- laplacian-into!
  [dest src spec spacing]
  (let [^doubles src* src
        ^doubles dest* dest
        {:keys [dimensions nx-int ny-int nz-int]} spec
        {:keys [dx dy dz]} spacing
        two-d? (= dimensions 2)
        plane-size (* nx-int ny-int)
        inv-dx2 (/ 1.0 (* dx dx))
        inv-dy2 (/ 1.0 (* dy dy))
        inv-dz2 (when (and (not two-d?) dz) (/ 1.0 (* dz dz)))
        planes (if two-d? 1 nz-int)]
    (dotimes [k planes]
      (dotimes [j ny-int]
        (dotimes [i nx-int]
          (let [idx (+ i (* nx-int (+ j (* ny-int k))))
                center (aget src* idx)
                east (if (< (inc i) nx-int) (aget src* (inc idx)) 0.0)
                west (if (> i 0) (aget src* (dec idx)) 0.0)
                north (if (< (inc j) ny-int)
                        (aget src* (+ idx nx-int))
                        0.0)
                south (if (> j 0)
                        (aget src* (- idx nx-int))
                        0.0)
                up (when (and (not two-d?) (< (inc k) nz-int))
                     (aget src* (+ idx plane-size)))
                down (when (and (not two-d?) (> k 0))
                       (aget src* (- idx plane-size)))
                term-x (* inv-dx2 (+ east west (* -2.0 center)))
                term-y (* inv-dy2 (+ north south (* -2.0 center)))
                term-z (if (and (not two-d?) inv-dz2)
                         (* inv-dz2 (+ (or up 0.0) (or down 0.0) (* -2.0 center)))
                         0.0)]
            (aset-double dest* idx (+ term-x term-y term-z))))))))

(defn- extract-interior
  [divergence spec]
  (let [{:keys [dimensions nx-int ny-int nz-int]} spec
        two-d? (= dimensions 2)]
    (if (has-interior? spec)
      (let [planes (if two-d? 1 nz-int)
            arr (double-array (* nx-int ny-int planes) 0.0)]
        (dotimes [k planes]
          (let [k-actual (if two-d? 0 (inc k))]
            (dotimes [j ny-int]
              (let [j-actual (inc j)]
                (dotimes [i nx-int]
                  (let [i-actual (inc i)
                        idx (+ i (* nx-int (+ j (* ny-int k))))
                        value (get-in divergence [k-actual j-actual i-actual] 0.0)]
                    (aset-double arr idx (double value))))))))
        arr)
      (double-array 0))))

(defn- embed-interior
  [spec arr]
  (let [{:keys [dimensions nx ny nz nx-int ny-int nz-int]} spec
        two-d? (= dimensions 2)
        volume (scalar-volume {:dimensions dimensions
                               :resolution {:nx nx :ny ny :nz (when (not two-d?) nz)}})]
    (if (has-interior? spec)
      (let [plane-size (* nx-int ny-int)
            planes (if two-d? 1 nz-int)]
        (loop [idx 0
               vol volume]
          (if (= idx (alength ^doubles arr))
            vol
            (let [k (if two-d? 0 (quot idx plane-size))
                  rem-idx (mod idx plane-size)
                  j (quot rem-idx nx-int)
                  i (mod rem-idx nx-int)
                  k-actual (if two-d? 0 (inc k))
                  j-actual (inc j)
                  i-actual (inc i)]
              (recur (inc idx)
                     (assoc-in vol [k-actual j-actual i-actual]
                               (aget ^doubles arr idx)))))))
      volume)))

(defn- dot-flat
  [^doubles a ^doubles b]
  (let [n (alength a)]
    (loop [i 0
           acc 0.0]
      (if (= i n)
        acc
        (recur (inc i)
               (+ acc (* (aget a i) (aget b i))))))))

(defn- axpy!
  [^doubles y ^doubles x alpha]
  (let [n (alength y)]
    (dotimes [i n]
      (aset-double y i
                   (+ (aget y i)
                      (* alpha (aget x i))))))
  y)

(defn- scal-add!
  [^doubles dst ^doubles src beta]
  (let [n (alength dst)]
    (dotimes [i n]
      (aset-double dst i
                   (+ (aget src i)
                      (* beta (aget dst i))))))
  dst)

(defn- solve-poisson
  [divergence geometry iterations]
  (let [spec (interior-spec geometry)]
    (if-not (has-interior? spec)
      (scalar-volume geometry)
      (let [rhs (extract-interior divergence spec)
            n (alength ^doubles rhs)
            phi (double-array n 0.0)
            r (double-array rhs)
            p (double-array rhs)
            Ap (double-array n 0.0)
            tol (max 1.0e-10 (* 1.0e-6 (Math/sqrt (dot-flat r r))))]
        (loop [iter iterations
               rr (dot-flat r r)]
          (if (or (zero? n)
                  (zero? iter)
                  (< (Math/sqrt rr) tol))
            (embed-interior spec phi)
            (let [_ (laplacian-into! Ap p spec (:spacing geometry))
                  pAp (dot-flat p Ap)
                  alpha (if (zero? pAp) 0.0 (/ rr pAp))]
              (axpy! phi p alpha)
              (axpy! r Ap (- alpha))
              (let [rr-new (dot-flat r r)
                    beta (if (zero? rr) 0.0 (/ rr-new rr))]
                (scal-add! p r beta)
                (recur (dec iter) rr-new)))))))))

;; -----------------------------------------------------------------------------
;; Boundary / energy conditioning
;; -----------------------------------------------------------------------------

(defn- subtract-gradient
  [velocity gradient geometry]
  (let [vel-vol (canonical-volume velocity geometry)
        grad-vol gradient]
    (map-indexed
     (fn [k plane]
       (map-indexed
        (fn [j row]
          (map-indexed
           (fn [i cell]
             (let [u0 (double (or (:u cell) 0.0))
                   v0 (double (or (:v cell) 0.0))
                   w0 (double (or (:w cell) 0.0))
                   {:keys [u v w]} (get-in grad-vol [k j i] {:u 0.0 :v 0.0 :w 0.0})
                   gu (double (or u 0.0))
                   gv (double (or v 0.0))
                   gw (double (or w 0.0))]
               {:u (- u0 gu)
                :v (- v0 gv)
                :w (- w0 gw)}))
           row))
        plane))
     vel-vol)))

(defn- enforce-boundaries
  [velocity geometry]
  (let [{:keys [dimensions]} geometry
        {:keys [nx ny nz]} (:resolution geometry)
        nz* (if (= dimensions 2) 1 (or nz 1))]
    (vec
     (for [k (range nz*)]
       (vec
        (for [j (range ny)]
          (vec
           (for [i (range nx)]
             (if (or (zero? i) (= i (dec nx))
                     (zero? j) (= j (dec ny))
                     (and (> nz* 1) (or (zero? k) (= k (dec nz*)))))
               {:u 0.0 :v 0.0 :w 0.0}
               (get-in velocity [k j i]))))))))))

(defn- limit-energy
  [velocity limit]
  (vec
   (for [plane velocity]
     (vec
      (for [row plane]
        (vec
         (for [{:keys [u v w]} row]
           (let [u* (double (or u 0.0))
                 v* (double (or v 0.0))
                 w* (double (or w 0.0))
                 mag (math/sqrt (+ (* u* u*) (* v* v*) (* w* w*)))]
             (if (> mag limit)
               (let [scale (/ limit (max mag 1.0e-9))]
                 {:u (* scale u*) :v (* scale v*) :w (* scale w*)})
               {:u u* :v v* :w w*})))))))))

(defn- max-abs
  [field]
  (reduce
   (fn [acc plane]
     (reduce
      (fn [acc2 row]
        (reduce
         (fn [acc3 value]
           (max acc3 (Math/abs (double value))))
         acc2 row))
      acc plane))
   0.0
   field))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn correct
  "Project a surrogate flow field onto a divergence-free manifold with boundary enforcement."
  [{:keys [geometry]} flow-field {:keys [iterations energy-limit]
                                  :or {iterations 40
                                       energy-limit 75.0}}]
  (let [geom (core/validate-geometry! geometry)
        volume (canonical-volume (:velocity flow-field) geom)]
    (if (nil? volume)
      {:flow-field flow-field
       :residuals {:max-divergence 0.0
                   :energy-limit energy-limit
                   :note :no-velocity-field}
       :confidence 1.0}
      (let [div (divergence (:velocity flow-field) geom)
            phi (solve-poisson div geom iterations)
            grad (gradient-volume phi geom)
            projected (-> (subtract-gradient (:velocity flow-field) grad geom)
                          (enforce-boundaries geom)
                          (limit-energy energy-limit))
            residual-field (divergence (restore-volume projected geom) geom)
            max-div (max-abs residual-field)
            confidence (-> (- 1.0 (/ max-div 1.0e-2))
                           (max 0.0)
                           (min 1.0))
            restored (restore-volume projected geom)]
        {:flow-field (assoc flow-field :velocity restored)
         :residuals {:max-divergence max-div
                     :energy-limit energy-limit}
         :confidence confidence}))))
