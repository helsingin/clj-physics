(ns dev.cfd-perf
  (:require [physics.cfd.corrector :as corrector]
            [physics.cfd.core :as core]))

(def ^:private solve-poisson-fn
  "Private access to the solver for timing the inner CG loop."
  (or (ns-resolve 'physics.cfd.corrector 'solve-poisson)
      (throw (ex-info "Could not resolve solve-poisson" {}))))

(def ^:private divergence-fn
  "Private access to divergence for building a realistic RHS."
  (or (ns-resolve 'physics.cfd.corrector 'divergence)
      (throw (ex-info "Could not resolve divergence" {}))))

(defn ^:private geometry
  [n dimensions]
  (let [spacing {:dx-m 1.0 :dy-m 1.0 :dz-m (when (= dimensions 3) 1.0)}
        extent {:lx-m (* n (:dx-m spacing))
                :ly-m (* n (:dy-m spacing))}
        extent (if (= dimensions 3)
                 (assoc extent :lz-m (* n (:dz-m spacing)))
                 extent)]
    {:type :cartesian-grid
     :dimensions dimensions
     :origin {:x 0.0 :y 0.0 :z (when (= dimensions 3) 0.0)}
     :extent extent
     :resolution {:nx n :ny n :nz (when (= dimensions 3) n)}
     :spacing spacing}))

(defn ^:private synthetic-velocity
  "Deterministic synthetic velocity field for benchmarking."
  [{:keys [dimensions resolution]}]
  (let [{:keys [nx ny nz]} resolution
        nz* (if (= dimensions 2) 1 (or nz 1))]
    (vec
     (for [k (range nz*)]
       (vec
        (for [j (range ny)]
          (vec
           (for [i (range nx)]
             (let [f (double (+ i (* 0.5 j) (* 0.25 k)))]
               {:u (Math/sin (/ f 7.0))
                :v (Math/cos (/ f 5.0))
                :w (if (= dimensions 2) 0.0 (Math/sin (/ f 9.0)))})))))))))

(defn ^:private measure-corrector
  [label geom flow {:keys [runs iterations parallel?]
                    :or {runs 3 iterations 40 parallel? false}}]
  ;; Warm-up run to load classes/JIT.
  (corrector/correct {:geometry geom} flow {:iterations iterations
                                            :parallel? parallel?})
  (let [samples (vec
                 (for [_ (range runs)]
                   (let [t0 (System/nanoTime)
                         result (corrector/correct {:geometry geom} flow {:iterations iterations
                                                                           :parallel? parallel?})
                         t1 (System/nanoTime)]
                     {:ms (/ (- t1 t0) 1.0e6)
                      :max-div (get-in result [:residuals :max-divergence])})))
        ms (map :ms samples)
        avg (/ (reduce + ms) (double runs))
        min-ms (apply min ms)
        max-ms (apply max ms)
        last-div (:max-div (peek samples))]
    (println (format "  %s avg=%.2f ms  min=%.2f  max=%.2f  last-max-div=%.3e"
                     label avg min-ms max-ms last-div))))

(defn ^:private measure-poisson-only
  [label geom div {:keys [runs iterations parallel?]
                   :or {runs 3 iterations 40 parallel? false}}]
  (println (format "  %s runs=%d iterations=%d" label runs iterations))
  ;; Warm-up to trigger JIT on the solver alone.
  (solve-poisson-fn div geom iterations {:parallel? parallel?})
  (let [samples (vec
                 (for [_ (range runs)]
                   (let [t0 (System/nanoTime)
                         phi (solve-poisson-fn div geom iterations {:parallel? parallel?})
                         t1 (System/nanoTime)
                         max-phi (apply max (map (fn [plane]
                                                   (apply max (map (fn [row] (apply max row)) plane)))
                                                 phi))]
                     {:ms (/ (- t1 t0) 1.0e6)
                      :max-phi max-phi})))
        ms (map :ms samples)
        avg (/ (reduce + ms) (double runs))
        min-ms (apply min ms)
        max-ms (apply max ms)
        last-max-phi (:max-phi (peek samples))]
    (println (format "    solver avg=%.2f ms  min=%.2f  max=%.2f  last-max-phi=%.3e"
                     avg min-ms max-ms last-max-phi))))

(defn run
  "Benchmark full corrector and inner Poisson solve over a set of grid sizes. Example:
     clj -X dev.cfd-perf/run :sizes '[32 64 100]' :runs 5 :iterations 40
   Options:
     :sizes        vector of grid edge sizes
     :runs         samples per size (default 3)
     :iterations   Poisson iterations (default 40)
     :dimensions   2 or 3 (default 3)
     :compare-parallel? run both serial and parallel variants"
  [{:keys [sizes runs iterations dimensions compare-parallel?]
    :or {sizes [32 64 100] runs 3 iterations 40 dimensions 3 compare-parallel? false}}]
  (doseq [n sizes]
    (let [geom (core/validate-geometry! (geometry n dimensions))
          flow {:velocity (synthetic-velocity geom)}
          div (divergence-fn (:velocity flow) geom)
          header (format "Grid %s^%s | runs=%d | iterations=%d"
                         n (if (= dimensions 2) 2 3) runs iterations)]
      (println header)
      (measure-corrector "corrector (serial)" geom flow {:runs runs :iterations iterations})
      (measure-poisson-only "Poisson-only (serial)" geom div {:runs runs :iterations iterations})
      (when compare-parallel?
        (measure-corrector "corrector (parallel)" geom flow {:runs runs :iterations iterations
                                                             :parallel? true})
        (measure-poisson-only "Poisson-only (parallel)" geom div {:runs runs :iterations iterations
                                                                  :parallel? true}))))
  :done)
