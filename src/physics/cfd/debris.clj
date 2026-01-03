(ns physics.cfd.debris
  "Debris/sand flow helper wrapping the surrogate + corrector pipeline for near-ground transport."
  (:require
   [physics.cfd.core :as core]
   [physics.cfd.surrogate :as surrogate]
   [physics.cfd.corrector :as corrector]))

(set! *warn-on-reflection* true)

(defn default-geometry
  []
  {:id :debris-default
   :type :cartesian-grid
   :dimensions 2
   :origin {:x -50.0 :y -50.0}
   :extent {:lx 100.0 :ly 100.0}
   :resolution {:nx 60 :ny 60}
   :spacing {:dx 1.666 :dy 1.666}})

(defn default-environment
  []
  core/fixture-neutral-atmosphere)

(defn- validate-geometry!
  [geometry]
  (let [geom (core/validate-geometry! geometry)]
    (when-not (= (:type geom) :cartesian-grid)
      (throw (ex-info "Debris helper requires cartesian grid geometry"
                      {:type (:type geom)})))
    (when (not= 2 (:dimensions geom))
      (throw (ex-info "Debris helper requires 2D geometry" {:dimensions (:dimensions geom)})))
    geom))

(defn predict
  [{:keys [geometry environment parameters corrector-options]
    :or {geometry (default-geometry)
         environment (default-environment)}}]
  (let [geom (validate-geometry! geometry)
        _ (core/validate-environment! environment)
        surrogate-out (surrogate/predict {:solver :debris
                                          :geometry geom
                                          :parameters parameters})
        corrected (corrector/correct {:geometry geom}
                                     (:flow-field surrogate-out)
                                     corrector-options)]
    (assoc corrected :metadata (:metadata surrogate-out))))
