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

(defn- flatten-velocity
  "Flatten nested velocity into contiguous u/v/w arrays sized nx*ny*nz*."
  [volume {:keys [dimensions]} {:keys [nx ny nz]}]
  (let [nz* (if (= dimensions 2) 1 (or nz 1))
        n (* nx ny nz*)
        u (double-array n 0.0)
        v (double-array n 0.0)
        w (double-array n 0.0)]
    (dotimes [k nz*]
      (let [plane (nth volume k)]
        (dotimes [j ny]
          (let [row (nth plane j)]
            (dotimes [i nx]
              (let [idx (+ i (* nx (+ j (* ny k))))
                    cell (nth row i)
                    u* (double (or (:u cell) 0.0))
                    v* (double (or (:v cell) 0.0))
                    w* (double (or (:w cell) 0.0))]
                (aset-double u idx u*)
                (aset-double v idx v*)
                (aset-double w idx w*)))))))
    {:u u :v v :w w}))

(defn- flatten-scalar
  "Flatten nested scalar volume into contiguous array."
  [volume {:keys [dimensions]} {:keys [nx ny nz]}]
  (let [nz* (if (= dimensions 2) 1 (or nz 1))
        n (* nx ny nz*)
        arr (double-array n 0.0)]
    (dotimes [k nz*]
      (let [plane (nth volume k)]
        (dotimes [j ny]
          (let [row (nth plane j)]
            (dotimes [i nx]
              (let [idx (+ i (* nx (+ j (* ny k))))
                    val (double (nth row i 0.0))]
                (aset-double arr idx val)))))))
    arr))

(defn- unflatten-scalar-volume
  "Convert flat scalar array back to nested [nz][ny][nx] volume."
  [arr {:keys [dimensions]} {:keys [nx ny nz]}]
  (let [nz* (if (= dimensions 2) 1 (or nz 1))]
    (vec
     (for [k (range nz*)]
       (vec
        (for [j (range ny)]
          (vec
           (for [i (range nx)]
             (let [idx (+ i (* nx (+ j (* ny k))))]
               (aget ^doubles arr idx))))))))))

(defn- unflatten-vector-volume
  "Convert flat u/v/w arrays back to nested vector-of-maps."
  [u v w {:keys [dimensions]} {:keys [nx ny nz]}]
  (let [nz* (if (= dimensions 2) 1 (or nz 1))]
    (vec
     (for [k (range nz*)]
       (vec
        (for [j (range ny)]
          (vec
           (for [i (range nx)]
             (let [idx (+ i (* nx (+ j (* ny k))))]
               {:u (aget ^doubles u idx)
                :v (aget ^doubles v idx)
                :w (aget ^doubles w idx)})))))))))

;; -----------------------------------------------------------------------------
;; Parallel helpers
;; -----------------------------------------------------------------------------

(defn- chunked-range
  "Return vector of {:start s :end e :idx i} covering [0,n)."
  [n chunk-size]
  (let [n* (long (max 0 n))
        chunk* (long (max 1 chunk-size))]
    (loop [start 0
           idx 0
           acc []]
      (if (>= start n*)
        acc
        (let [end (min n* (+ start chunk*))]
          (recur end (inc idx) (conj acc {:start start :end end :idx idx})))))))

(defn- run-chunks!
  "Run f on each chunk, using the common ForkJoinPool when multiple chunks are present."
  [chunks f]
  (if (<= (count chunks) 1)
    (doseq [c chunks] (f c))
    (let [pool (java.util.concurrent.ForkJoinPool/commonPool)
          futures (into-array java.util.concurrent.CompletableFuture
                              (map (fn [c]
                                     (java.util.concurrent.CompletableFuture/runAsync
                                      ^Runnable (fn [] (f c))
                                      pool))
                                   chunks))]
      (.join (java.util.concurrent.CompletableFuture/allOf futures)))))

;; -----------------------------------------------------------------------------
;; Differential operators
;; -----------------------------------------------------------------------------

(defn- divergence
  [velocity geometry]
  (let [{:keys [resolution spacing dimensions]} geometry
        {:keys [nx ny nz]} resolution
        {:keys [dx-m dy-m dz-m]} spacing
        nz* (if (= dimensions 2) 1 (or nz 1))
        inv2dx (/ 1.0 (* 2.0 dx-m))
        inv2dy (/ 1.0 (* 2.0 dy-m))
        inv2dz (when dz-m (/ 1.0 (* 2.0 dz-m)))
        vol (canonical-volume velocity geometry)
        {:keys [u v w]} (flatten-velocity vol geometry resolution)
        div (double-array (* nx ny nz*) 0.0)]
    (dotimes [k nz*]
      (dotimes [j ny]
        (dotimes [i nx]
          (let [idx (+ i (* nx (+ j (* ny k))))
                i+ (clamp-index (inc i) (dec nx))
                i- (clamp-index (dec i) (dec nx))
                j+ (clamp-index (inc j) (dec ny))
                j- (clamp-index (dec j) (dec ny))
                k+ (clamp-index (inc k) (dec nz*))
                k- (clamp-index (dec k) (dec nz*))
                idx-e (+ i+ (* nx (+ j (* ny k))))
                idx-w (+ i- (* nx (+ j (* ny k))))
                idx-n (+ i (* nx (+ j+ (* ny k))))
                idx-s (+ i (* nx (+ j- (* ny k))))
                idx-up (+ i (* nx (+ j (* ny k+))))
                idx-dn (+ i (* nx (+ j (* ny k-))))
                u+ (aget ^doubles u idx-e)
                u- (aget ^doubles u idx-w)
                v+ (aget ^doubles v idx-n)
                v- (aget ^doubles v idx-s)
                w+ (if inv2dz (aget ^doubles w idx-up) 0.0)
                w- (if inv2dz (aget ^doubles w idx-dn) 0.0)
                val (+ (* inv2dx (- u+ u-))
                       (* inv2dy (- v+ v-))
                       (if inv2dz (* inv2dz (- w+ w-)) 0.0))]
            (aset-double div idx val)))))
    (unflatten-scalar-volume div geometry resolution)))

(defn- gradient-volume
  [phi geometry]
  (let [{:keys [resolution spacing dimensions]} geometry
        {:keys [nx ny nz]} resolution
        {:keys [dx-m dy-m dz-m]} spacing
        nz* (if (= dimensions 2) 1 (or nz 1))
        inv2dx (/ 1.0 (* 2.0 dx-m))
        inv2dy (/ 1.0 (* 2.0 dy-m))
        inv2dz (when dz-m (/ 1.0 (* 2.0 dz-m)))
        phi-flat (flatten-scalar phi geometry resolution)
        n (* nx ny nz*)
        gu (double-array n 0.0)
        gv (double-array n 0.0)
        gw (double-array n 0.0)]
    (dotimes [k nz*]
      (dotimes [j ny]
        (dotimes [i nx]
          (let [idx (+ i (* nx (+ j (* ny k))))
                i+ (clamp-index (inc i) (dec nx))
                i- (clamp-index (dec i) (dec nx))
                j+ (clamp-index (inc j) (dec ny))
                j- (clamp-index (dec j) (dec ny))
                k+ (clamp-index (inc k) (dec nz*))
                k- (clamp-index (dec k) (dec nz*))
                idx-e (+ i+ (* nx (+ j (* ny k))))
                idx-w (+ i- (* nx (+ j (* ny k))))
                idx-n (+ i (* nx (+ j+ (* ny k))))
                idx-s (+ i (* nx (+ j- (* ny k))))
                idx-up (+ i (* nx (+ j (* ny k+))))
                idx-dn (+ i (* nx (+ j (* ny k-))))
                g-u (* inv2dx (- (aget ^doubles phi-flat idx-e)
                                 (aget ^doubles phi-flat idx-w)))
                g-v (* inv2dy (- (aget ^doubles phi-flat idx-n)
                                 (aget ^doubles phi-flat idx-s)))
                g-w (if inv2dz
                      (* inv2dz (- (aget ^doubles phi-flat idx-up)
                                   (aget ^doubles phi-flat idx-dn)))
                      0.0)
                len (alength ^doubles gu)]
            (when (or (neg? idx) (>= idx len))
              (throw (ex-info "gradient idx overflow"
                              {:idx idx :len len
                               :i i :j j :k k
                               :nx nx :ny ny :nz nz*
                               :idx-e idx-e :idx-w idx-w
                               :idx-n idx-n :idx-s idx-s
                               :idx-up idx-up :idx-dn idx-dn})))
            (aset-double gu idx g-u)
            (aset-double gv idx g-v)
            (aset-double gw idx g-w)))))
    (unflatten-vector-volume gu gv gw geometry resolution)))

(defn- laplacian-into!
  [dest src spec spacing]
  (let [^doubles src* src
        ^doubles dest* dest
        {:keys [dimensions nx-int ny-int nz-int]} spec
        {:keys [dx-m dy-m dz-m]} spacing
        two-d? (= dimensions 2)
        plane-size (* nx-int ny-int)
        inv-dx2 (/ 1.0 (* dx-m dx-m))
        inv-dy2 (/ 1.0 (* dy-m dy-m))
        inv-dz2 (when (and (not two-d?) dz-m) (/ 1.0 (* dz-m dz-m)))
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

(defn- laplacian-into-parallel!
  [dest src spec spacing]
  (let [{:keys [dimensions nx-int ny-int nz-int]} spec
        two-d? (= dimensions 2)
        planes (if two-d? 1 nz-int)]
    (if (<= planes 1)
      (laplacian-into! dest src spec spacing)
      (let [^doubles src* src
            ^doubles dest* dest
            {:keys [dx-m dy-m dz-m]} spacing
            plane-size (* nx-int ny-int)
            inv-dx2 (/ 1.0 (* dx-m dx-m))
            inv-dy2 (/ 1.0 (* dy-m dy-m))
            inv-dz2 (when (and (not two-d?) dz-m) (/ 1.0 (* dz-m dz-m)))
            ;; Aim for ~1 task per core to reduce variance on mid-size grids.
            procs (.availableProcessors (Runtime/getRuntime))
            chunk-size (max 1 (long (math/ceil (/ planes procs))))
            chunks (chunked-range planes chunk-size)]
        (run-chunks!
         chunks
         (fn [{:keys [start end]}]
           (dotimes [k (- end start)]
             (let [k-abs (+ start k)]
               (dotimes [j ny-int]
                 (dotimes [i nx-int]
                   (let [idx (+ i (* nx-int (+ j (* ny-int k-abs))))
                         center (aget src* idx)
                         east (if (< (inc i) nx-int) (aget src* (inc idx)) 0.0)
                         west (if (> i 0) (aget src* (dec idx)) 0.0)
                         north (if (< (inc j) ny-int)
                                 (aget src* (+ idx nx-int))
                                 0.0)
                         south (if (> j 0)
                                 (aget src* (- idx nx-int))
                                 0.0)
                         up (when (and (not two-d?) (< (inc k-abs) nz-int))
                              (aget src* (+ idx plane-size)))
                         down (when (and (not two-d?) (> k-abs 0))
                                (aget src* (- idx plane-size)))
                         term-x (* inv-dx2 (+ east west (* -2.0 center)))
                         term-y (* inv-dy2 (+ north south (* -2.0 center)))
                         term-z (if (and (not two-d?) inv-dz2)
                                  (* inv-dz2 (+ (or up 0.0) (or down 0.0) (* -2.0 center)))
                                  0.0)]
                     (aset-double dest* idx (+ term-x term-y term-z)))))))))
        dest))))

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

(defn- dot-flat-parallel
  [^doubles a ^doubles b]
  (let [n (alength a)]
    (cond
      (zero? n) 0.0
      (<= n 8192) (dot-flat a b)
      :else (let [procs (.availableProcessors (Runtime/getRuntime))
                  chunk-size (max 16384 (long (math/ceil (/ n procs))))
                  chunks (vec (chunked-range n chunk-size))]
              (if (<= (count chunks) 1)
                (dot-flat a b)
                (let [partials (double-array (count chunks) 0.0)]
                  (run-chunks!
                   chunks
                   (fn [{:keys [start end idx]}]
                     (loop [i start
                            acc 0.0]
                       (if (= i end)
                         (aset-double partials idx acc)
                         (recur (inc i)
                                (+ acc (* (aget a i) (aget b i))))))))
                  (let [len (alength partials)]
                    (loop [i 0
                           acc 0.0]
                      (if (= i len)
                        acc
                        (recur (inc i) (+ acc (aget partials i))))))))))))

(defn- axpy!
  [^doubles y ^doubles x alpha]
  (let [n (alength y)]
    (dotimes [i n]
      (aset-double y i
                   (+ (aget y i)
                      (* alpha (aget x i))))))
  y)

(defn- axpy-parallel!
  [^doubles y ^doubles x alpha]
  (let [n (alength y)
        procs (.availableProcessors (Runtime/getRuntime))
        chunk-size (max 16384 (long (math/ceil (/ n procs))))
        chunks (vec (chunked-range n chunk-size))]
    (if (<= (count chunks) 1)
      (axpy! y x alpha)
      (do
        (run-chunks!
         chunks
         (fn [{:keys [start end]}]
           (dotimes [off (- end start)]
             (let [idx (+ start off)]
               (aset-double y idx
                            (+ (aget y idx)
                               (* alpha (aget x idx))))))))
        y))))

(defn- scal-add!
  [^doubles dst ^doubles src beta]
  (let [n (alength dst)]
    (dotimes [i n]
      (aset-double dst i
                   (+ (aget src i)
                      (* beta (aget dst i))))))
  dst)

(defn- scal-add-parallel!
  [^doubles dst ^doubles src beta]
  (let [n (alength dst)
        procs (.availableProcessors (Runtime/getRuntime))
        chunk-size (max 16384 (long (math/ceil (/ n procs))))
        chunks (vec (chunked-range n chunk-size))]
    (if (<= (count chunks) 1)
      (scal-add! dst src beta)
      (do
        (run-chunks!
         chunks
         (fn [{:keys [start end]}]
           (dotimes [off (- end start)]
             (let [idx (+ start off)]
               (aset-double dst idx
                            (+ (aget src idx)
                               (* beta (aget dst idx))))))))
        dst))))

(defn- solve-poisson
  ([divergence geometry iterations]
   (solve-poisson divergence geometry iterations {}))
  ([divergence geometry iterations {:keys [parallel?]
                                    :or {parallel? false}}]
   (let [spec (interior-spec geometry)]
     (if-not (has-interior? spec)
       (scalar-volume geometry)
       (let [rhs (extract-interior divergence spec)
             n (alength ^doubles rhs)
             ;; Avoid thread overhead on tiny problems even if parallel? is true.
             use-parallel? (and parallel? (> n 150000))
             lap-fn (if use-parallel? laplacian-into-parallel! laplacian-into!)
             dot-fn (if use-parallel? dot-flat-parallel dot-flat)
             axpy-fn (if use-parallel? axpy-parallel! axpy!)
             scal-add-fn (if use-parallel? scal-add-parallel! scal-add!)
             phi (double-array n 0.0)
             r (double-array rhs)
             p (double-array rhs)
             Ap (double-array n 0.0)
             tol (max 1.0e-10 (* 1.0e-6 (Math/sqrt (dot-fn r r))))]
         (loop [iter iterations
                rr (dot-fn r r)]
           (if (or (zero? n)
                   (zero? iter)
                   (< (Math/sqrt rr) tol))
             (embed-interior spec phi)
             (let [_ (lap-fn Ap p spec (:spacing geometry))
                   pAp (dot-fn p Ap)
                   alpha (if (zero? pAp) 0.0 (/ rr pAp))]
               (axpy-fn phi p alpha)
               (axpy-fn r Ap (- alpha))
               (let [rr-new (dot-fn r r)
                     beta (if (zero? rr) 0.0 (/ rr-new rr))]
                 (scal-add-fn p r beta)
                 (recur (dec iter) rr-new))))))))))

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
  [{:keys [geometry]} flow-field {:keys [iterations energy-limit parallel?]
                                  :or {iterations 40
                                       energy-limit 75.0
                                       parallel? false}}]
  (let [geom (core/validate-geometry! geometry)
        volume (canonical-volume (:velocity flow-field) geom)]
    (if (nil? volume)
      {:flow-field flow-field
       :residuals {:max-divergence 0.0
                   :energy-limit energy-limit
                   :note :no-velocity-field}
       :confidence 1.0}
      (let [div (divergence (:velocity flow-field) geom)
            phi (solve-poisson div geom iterations {:parallel? parallel?})
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
