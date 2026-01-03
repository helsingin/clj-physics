(ns physics.cfd.plume
  "High-level plume modeling helper that wires geometry/environment normalization into the surrogate
   and corrector pipeline."
  (:require
   [physics.cfd.core :as core]
   [physics.cfd.surrogate :as surrogate]
   [physics.cfd.corrector :as corrector]))

(set! *warn-on-reflection* true)

(defn default-geometry
  []
  {:id :plume-default
   :type :cartesian-grid
   :dimensions 3
   :origin {:x 0.0 :y 0.0 :z 0.0}
   :extent {:lx 200.0 :ly 200.0 :lz 120.0}
   :resolution {:nx 40 :ny 40 :nz 24}
   :spacing {:dx 5.0 :dy 5.0 :dz 5.0}})

(defn- validate-geometry!
  [geometry]
  (let [geom (core/validate-geometry! geometry)]
    (when-not (= (:type geom) :cartesian-grid)
      (throw (ex-info "Plume helper requires cartesian grid geometry"
                      {:type (:type geom)})))
    (when (not= (:dimensions geom) 3)
      (throw (ex-info "Plume helper requires 3D geometry"
                      {:dimensions (:dimensions geom)})))
    (let [nz (get-in geom [:resolution :nz])
          dz (get-in geom [:spacing :dz])
          lz (get-in geom [:extent :lz])]
      (when (or (nil? nz) (< nz 2))
        (throw (ex-info "Resolution must include nz >= 2" {:resolution (:resolution geom)})))
      (when (or (nil? dz) (<= dz 0.0))
        (throw (ex-info "Spacing must include dz > 0" {:spacing (:spacing geom)})))
      (when (or (nil? lz) (<= lz 0.0))
        (throw (ex-info "Extent must include positive :lz" {:extent (:extent geom)}))))
    geom))

(defn predict
  [{:keys [geometry environment parameters corrector-options]
    :or {geometry (default-geometry)
         environment core/fixture-neutral-atmosphere}}]
  (let [geom (validate-geometry! geometry)
        env (core/validate-environment! environment)
        surrogate-out (surrogate/predict {:solver :plume
                                          :geometry geom
                                          :environment env
                                          :parameters parameters})
        corrected (corrector/correct {:geometry geom}
                                     (:flow-field surrogate-out)
                                     corrector-options)]
    (-> corrected
        (assoc :metadata (:metadata surrogate-out)))))
