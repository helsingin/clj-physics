(ns physics.cfd.core
  "Shared data structures, validation helpers, and fixtures for computational fluid dynamics
   requests.  Every solver (classical, reduced-order, or ML surrogate) consumes the same
   geometry/environment/platform maps so higher layers can reason about environmental flows
   without caring about the underlying numerical method."
  (:require
   [clojure.set :as set]
   [malli.core :as m]
   [malli.error :as me]))

(set! *warn-on-reflection* true)


;; ---------------------------------------------------------------------------
;; Scalar predicates
;; ---------------------------------------------------------------------------

(def ^:private finite-double?
  "Returns true when x is a finite IEEE-754 value."
  (every-pred number? #(Double/isFinite (double %))))

(def ^:private non-negative-double? (every-pred finite-double? #(>= (double %) 0.0)))
(def ^:private positive-double? (every-pred finite-double? #(> (double %) 0.0)))
(def ^:private bounded-unit? (every-pred finite-double? #(>= (double %) 0.0) #(<= (double %) 1.0)))
(def ^:private non-negative-int? (every-pred int? #(>= ^int % 0)))

(def finite-double-schema [:fn {:error/message "finite double"} finite-double?])
(def positive-double-schema [:fn {:error/message "positive finite double"} positive-double?])
(def non-negative-double-schema [:fn {:error/message "non-negative finite double"} non-negative-double?])
(def bounded-unit-schema [:fn {:error/message "value must lie in [0,1]"} bounded-unit?])
(def pos-int-schema [:fn {:error/message "positive integer"} #(pos-int? %)])
(def non-negative-int-schema [:fn {:error/message "non-negative integer"} non-negative-int?])


;; ---------------------------------------------------------------------------
;; Geometry schema
;; ---------------------------------------------------------------------------

(def coordinate-schema
  "Generic 3D coordinate map. 2D geometries omit :z."
  [:map {:closed true}
   [:x finite-double-schema]
   [:y finite-double-schema]
   [:z {:optional true} finite-double-schema]])

(def resolution-schema
  [:map {:closed true}
   [:nx pos-int-schema]
   [:ny pos-int-schema]
   [:nz {:optional true} pos-int-schema]])

(def spacing-schema
  [:map {:closed true}
   [:dx positive-double-schema]
   [:dy positive-double-schema]
   [:dz {:optional true} positive-double-schema]])

(def mesh-schema
  [:map {:closed true}
   [:vertices [:sequential coordinate-schema]]
   [:faces [:sequential
            [:tuple non-negative-int-schema non-negative-int-schema non-negative-int-schema]]]])

(def signed-distance-schema
  [:map {:closed true}
   [:grid-shape resolution-schema]
   [:samples [:sequential finite-double-schema]]
   [:outside-value finite-double-schema]])

(def geometry-schema
  "Canonical geometry description for CFD solvers. Only the keys required by the chosen :type
   are expected:
     - :cartesian-grid → {:resolution .. :spacing ..}
     - :surface-mesh   → {:surface mesh}
     - :signed-distance→ {:sdf ..}"
  [:map {:closed true}
   [:id {:optional true} keyword?]
   [:type [:enum :cartesian-grid :surface-mesh :signed-distance]]
   [:dimensions [:enum 2 3]]
   [:origin coordinate-schema]
   [:extent [:map {:closed true}
             [:lx positive-double-schema]
             [:ly positive-double-schema]
             [:lz {:optional true} positive-double-schema]]]
   [:resolution {:optional true} resolution-schema]
   [:spacing {:optional true} spacing-schema]
   [:surface {:optional true} mesh-schema]
   [:sdf {:optional true} signed-distance-schema]
   [:metadata {:optional true} map?]])


;; ---------------------------------------------------------------------------
;; Environment schema
;; ---------------------------------------------------------------------------

(def wind-profile-schema
  [:map {:closed true}
   [:reference coordinate-schema]
   [:gust-intensity {:optional true} bounded-unit-schema]
   [:shear {:optional true} finite-double-schema]
   [:turbulence {:optional true} bounded-unit-schema]])

(def fluid-properties-schema
  [:map {:closed true}
   [:density positive-double-schema]          ; kg/m^3
   [:dynamic-viscosity positive-double-schema] ; Pa·s
   [:temperature finite-double-schema]        ; Kelvin
   [:pressure finite-double-schema]])         ; Pa

(def environment-schema
  [:map {:closed true}
   [:frame {:optional true} [:enum :inertial :earth-fixed :body]]
   [:fluid [:enum :air :water :custom]]
   [:properties fluid-properties-schema]
   [:wind {:optional true} wind-profile-schema]
   [:microphysics {:optional true}
    [:map {:closed true}
     [:humidity {:optional true} bounded-unit-schema]
     [:particulate-density {:optional true} non-negative-double-schema]]]
   [:stability {:optional true}
    [:map {:closed true}
     [:richardson {:optional true} finite-double-schema]
     [:brunt-vaisala {:optional true} finite-double-schema]]]
   [:metadata {:optional true} map?]])


;; ---------------------------------------------------------------------------
;; Platform schema
;; ---------------------------------------------------------------------------

(def inertia-tensor-schema
  [:map {:closed true}
   [:ixx positive-double-schema]
   [:iyy positive-double-schema]
   [:izz positive-double-schema]
   [:ixy {:optional true} finite-double-schema]
   [:ixz {:optional true} finite-double-schema]
   [:iyz {:optional true} finite-double-schema]])

(def operating-regime-schema
  [:map {:closed true}
   [:reynolds [:tuple positive-double-schema positive-double-schema]]
   [:mach {:optional true} [:tuple non-negative-double-schema non-negative-double-schema]]
   [:froude {:optional true} [:tuple non-negative-double-schema non-negative-double-schema]]])

(def platform-schema
  [:map {:closed true}
   [:id keyword?]
   [:category [:enum :uas :ugv :usv :umv :structure]]
   [:reference-length positive-double-schema]
   [:reference-area positive-double-schema]
   [:mass positive-double-schema]
   [:inertia {:optional true} inertia-tensor-schema]
   [:operating-regime {:optional true} operating-regime-schema]
  [:control-surfaces {:optional true}
   [:map-of keyword?
    [:map {:closed true}
     [:area positive-double-schema]
     [:deflection-range [:tuple finite-double-schema finite-double-schema]]]]]
   [:metadata {:optional true} map?]])


;; ---------------------------------------------------------------------------
;; Validation helpers
;; ---------------------------------------------------------------------------

(defn- explain
  [schema value label]
  (let [explanation (m/explain schema value)]
    (throw (ex-info (str "Invalid " label)
                    {:errors (me/humanize explanation)
                     :value value
                     :schema schema}))))

(defn validate-geometry!
  "Ensures a geometry map is structurally sound and internally consistent."
  [geometry]
  (when-not (m/validate geometry-schema geometry)
    (explain geometry-schema geometry "geometry"))
  (let [{:keys [dimensions origin extent resolution spacing surface sdf type]} geometry]
    (when (and (= dimensions 2) (contains? origin :z))
      (throw (ex-info "2D geometries must omit :z from :origin"
                      {:origin origin :dimensions dimensions})))
    (when (and (= dimensions 2) (contains? extent :lz))
      (throw (ex-info "2D geometries must omit :lz from :extent" {:extent extent})))
    (when (and (= type :cartesian-grid)
               (not (and resolution spacing)))
      (throw (ex-info "Cartesian grids require :resolution and :spacing" {:geometry geometry})))
    (when (and (= type :surface-mesh) (nil? surface))
      (throw (ex-info "Surface mesh geometries require :surface" {:geometry geometry})))
    (when (and (= type :signed-distance) (nil? sdf))
      (throw (ex-info "Signed distance geometries require :sdf" {:geometry geometry}))))
  geometry)

(defn validate-environment!
  [environment]
  (when-not (m/validate environment-schema environment)
    (explain environment-schema environment "environment"))
  environment)

(defn validate-platform!
  [platform]
  (when-not (m/validate platform-schema platform)
    (explain platform-schema platform "platform"))
  platform)


;; ---------------------------------------------------------------------------
;; Deterministic fixtures for tests and examples
;; ---------------------------------------------------------------------------

(def fixture-urban-canyon-geometry
  "A 3D urban canyon extracted as a modest surface mesh."
  {:id :urban-canyon-simplified
   :type :surface-mesh
   :dimensions 3
   :origin {:x 0.0 :y 0.0 :z 0.0}
   :extent {:lx 120.0 :ly 80.0 :lz 60.0}
   :surface {:vertices [{:x 0.0 :y 0.0 :z 0.0}
                        {:x 120.0 :y 0.0 :z 0.0}
                        {:x 120.0 :y 80.0 :z 0.0}
                        {:x 0.0 :y 80.0 :z 0.0}
                        {:x 40.0 :y 30.0 :z 30.0}
                        {:x 80.0 :y 50.0 :z 45.0}]
             :faces [[0 1 4] [1 2 5] [2 3 5] [3 0 4] [4 5 2]]}})

(def fixture-neutral-atmosphere
  "ISA-like reference atmosphere with a mild gust corridor."
  {:frame :earth-fixed
   :fluid :air
   :properties {:density 1.225
                :dynamic-viscosity 1.81e-5
                :temperature 288.15
                :pressure 101325.0}
   :wind {:reference {:x 5.0 :y 0.5 :z 0.0}
          :gust-intensity 0.25
          :shear 0.08
          :turbulence 0.18}
   :microphysics {:humidity 0.45
                  :particulate-density 1.0e-5}
   :stability {:richardson 0.12
               :brunt-vaisala 0.014}})

(def fixture-quadrotor-platform
  "Reference UAS platform identical across tests for determinism."
  {:id :quadrotor-reference
   :category :uas
   :reference-length 0.45
   :reference-area 0.20
   :mass 6.2
   :inertia {:ixx 0.32 :iyy 0.34 :izz 0.58}
   :operating-regime {:reynolds [1.0e4 4.0e5]
                      :mach [0.0 0.25]}
   :metadata {:description "Small quadrotor used for urban canyon CFD fixtures"}})

;; Validate fixtures on load to guarantee determinism.
(validate-geometry! fixture-urban-canyon-geometry)
(validate-environment! fixture-neutral-atmosphere)
(validate-platform! fixture-quadrotor-platform)


;; ---------------------------------------------------------------------------
;; Utility helpers
;; ---------------------------------------------------------------------------

(defn geometry-dof
  "Returns the number of spatial degrees of freedom represented by the geometry."
  [{:keys [type resolution surface sdf dimensions] :as geometry}]
  (validate-geometry! geometry)
  (case type
    :cartesian-grid (let [{:keys [nx ny nz]} resolution]
                      (if (= dimensions 2)
                        (* nx ny)
                        (* nx ny nz)))
    :surface-mesh (count (:faces surface))
    :signed-distance (let [{:keys [grid-shape]} sdf
                           {:keys [nx ny nz]} grid-shape]
                       (if (= dimensions 2)
                         (* nx ny)
                         (* nx ny nz)))))

(defn reference-fluid-speed
  "Computes a characteristic fluid speed sqrt(2*pressure/density) for quick scaling heuristics."
  [{:keys [properties] :as environment}]
  (validate-environment! environment)
  (let [{:keys [pressure density]} properties]
    (Math/sqrt (Math/abs (/ (* 2.0 pressure) density)))))

(defn platform-state-vector
  "Returns the canonical state vector [mass, ref-length, ref-area] used by surrogates."
  [{:keys [mass reference-length reference-area] :as platform}]
  (validate-platform! platform)
  [mass reference-length reference-area])
