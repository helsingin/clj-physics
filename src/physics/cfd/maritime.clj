(ns physics.cfd.maritime
  "Maritime flow helper: prepares shallow-water geometries, water environments, and runs the
   surrogate + corrector pipeline."
  (:require
   [physics.cfd.core :as core]
   [physics.cfd.surrogate :as surrogate]
   [physics.cfd.corrector :as corrector]))

(set! *warn-on-reflection* true)

(defn default-geometry
  []
  {:id :maritime-default
   :type :cartesian-grid
   :dimensions 3
   :origin {:x 0.0 :y 0.0 :z 0.0}
   :extent {:lx 500.0 :ly 200.0 :lz 6.0}
   :resolution {:nx 60 :ny 24 :nz 6}
   :spacing {:dx 8.333 :dy 8.333 :dz 1.0}})

(defn default-environment
  []
  {:fluid :water
   :properties {:density 1025.0
                :dynamic-viscosity 0.0011
                :temperature 288.15
                :pressure 101325.0}})

(defn- validate-geometry!
  [geometry]
  (let [geom (core/validate-geometry! geometry)]
    (when-not (= (:type geom) :cartesian-grid)
      (throw (ex-info "Maritime helper requires cartesian grid geometry"
                      {:type (:type geom)})))
    (when (not= 3 (:dimensions geom))
      (throw (ex-info "Maritime helper requires 3D geometry" {:dimensions (:dimensions geom)})))
    geom))

(defn predict
  [{:keys [geometry environment parameters corrector-options]
    :or {geometry (default-geometry)
         environment (default-environment)}}]
  (let [geom (validate-geometry! geometry)
        env (core/validate-environment! environment)
        surrogate-out (surrogate/predict {:solver :maritime
                                          :geometry geom
                                          :environment env
                                          :parameters parameters})
        corrected (corrector/correct {:geometry geom}
                                     (:flow-field surrogate-out)
                                     corrector-options)]
    (assoc corrected :metadata (:metadata surrogate-out))))
