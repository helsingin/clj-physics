
(ns physics.cfd.surrogate
  "Spatial surrogates for fluid prediction.  Provides deterministic analytical fallbacks and a hook
   for plugging in future ML models.  Returns structured flow fields plus provenance metadata so
   downstream correctors and translators can reason about confidence."
  (:require
   [clojure.java.io :as io]
   [clojure.math :as math]
   [clojure.tools.logging :as log]
   [physics.cfd.core :as cfd-core])
  (:import
   [java.security MessageDigest]
   [java.time Instant]))

(set! *warn-on-reflection* true)

(def ^:private sqrt-two-pi (Math/sqrt (* 2.0 Math/PI)))

(defonce ^:private model-registry (atom {}))

(defn- sha256-bytes
  [^bytes bytes]
  (let [digest (.digest (doto (MessageDigest/getInstance "SHA-256")
                          (.update bytes)))]
    (format "%064x" (BigInteger. 1 digest))))

(defn register-model!
  "Register an ML surrogate weight file plus normalization metadata.

   options = {:id :meshgraphnet-v1
              :path \"models/mgn.onnx\"
              :backend :onnx
              :normalization {:mean 0.0 :std 1.0}
              :metadata {:training-run \"2025-02-10\"}}"
  [{:keys [id path backend normalization metadata] :as opts}]
  (when-not id
    (throw (ex-info "Model registration requires :id" {:options opts})))
  (when-not path
    (throw (ex-info "Model registration requires :path" {:id id})))
  (let [file (io/file path)]
    (when-not (.exists file)
      (throw (ex-info "Model file not found" {:id id :path path})))
    (let [bytes (java.nio.file.Files/readAllBytes (.toPath file))
          checksum (sha256-bytes bytes)
          entry {:id id
                 :path (.getAbsolutePath file)
                 :backend (or backend :onnx)
                 :normalization normalization
                 :metadata metadata
                 :checksum checksum
                 :loaded-at (.toString (Instant/now))}]
      (swap! model-registry assoc id entry)
      (log/infof "Registered CFD surrogate %s checksum=%s" (name id) checksum)
      entry)))

(defn list-models
  []
  (vals @model-registry))

(defn model-entry
  [id]
  (get @model-registry id))

(defn- positive-int? [v]
  (and (int? v) (pos? v)))

(defn- valid-resolution? [{:keys [nx ny nz]}]
  (and (positive-int? nx)
       (positive-int? ny)
       (or (nil? nz) (positive-int? nz))))

(defn- ensure-cartesian!
  [{:keys [type resolution] :as geometry}]
  (when-not (= type :cartesian-grid)
    (throw (ex-info "Analytical surrogate requires cartesian grid geometry"
                    {:type type})))
  (when-not (valid-resolution? resolution)
    (throw (ex-info "Resolution must declare positive nx/ny"
                    {:resolution resolution})))
  geometry)

(defn- gaussian-vortex
  [{:keys [origin extent resolution]} {:keys [strength radius center]}]
  (let [{:keys [nx ny]} resolution
        {:keys [lx ly]} extent
        {:keys [x y]} origin
        {:keys [cx cy]} center
        dx (/ lx (max 1 (dec nx)))
        dy (/ ly (max 1 (dec ny)))]
    (let [plane
          (vec
           (for [j (range ny)]
             (vec
              (for [i (range nx)]
                (let [px (+ x (* i dx))
                      py (+ y (* j dy))
                      rx (- px cx)
                      ry (- py cy)
                      r2 (+ (* rx rx) (* ry ry) 1.0e-9)
                      magnitude (* strength (math/exp (/ (- r2) (* 2.0 radius radius))))]
                  {:u (* -1.0 magnitude (/ ry (math/sqrt r2)))
                   :v (* magnitude (/ rx (math/sqrt r2)))
                   :w 0.0})))))]
      (if-let [nz (get-in resolution [:nz])]
        (vec (repeat nz plane))
        plane))))

(defn- gaussian-plume
  [{:keys [resolution extent origin]} {:keys [emission-rate wind-speed stability]}]
  (let [{:keys [nx ny nz]} resolution
        {:keys [lx ly lz]} extent
        {:keys [x y z]} origin
        dx (/ lx (max 1 (dec nx)))
        dy (/ ly (max 1 (dec ny)))
        dz (/ lz (max 1 (dec nz)))
        sigma-y (max 1.0 (* stability 10.0))
        sigma-z (max 1.0 (* stability 5.0))]
    (vec
     (for [k (range nz)]
       (vec
        (for [j (range ny)]
          (vec
           (for [i (range nx)]
             (let [px (+ x (* i dx))
                   py (+ y (* j dy))
                   pz (+ z (* k dz))
                   exp-y (math/exp (/ (* -1.0 (- py y) (- py y)) (* 2 sigma-y sigma-y)))
                   exp-z (math/exp (/ (* -1.0 (- pz z) (- pz z)) (* 2 sigma-z sigma-z)))
                   conc (double (/ (* emission-rate exp-y exp-z)
                                   (* wind-speed sigma-y sigma-z sqrt-two-pi)))]
               conc)))))))))

(defn- sinusoidal-wave-field
  [{:keys [origin extent resolution]} {:keys [wave-height wave-length current-speed]
                                       :or {wave-height 1.0
                                            wave-length 30.0
                                            current-speed 1.5}}]
  (let [{:keys [nx ny nz]} resolution
        {:keys [lx ly lz]} extent
        {:keys [x y z]} origin
        dx (/ lx (max 1 (dec nx)))
        dy (/ ly (max 1 (dec ny)))
        dz (/ lz (max 1 (dec nz)))
        k (/ (* 2.0 Math/PI) (max wave-length 1.0))
        g 9.80665
        omega (math/sqrt (* g k))
        amp wave-height]
    (vec
     (for [kz (range nz)]
       (vec
        (for [jy (range ny)]
          (vec
           (for [ix (range nx)]
             (let [px (+ x (* ix dx))
                   py (+ y (* jy dy))
                   pz (+ z (* kz dz))
                   phase (+ (* k px) (* 0.1 py))
                   decay (math/exp (* -1.0 k pz))
                   u (+ current-speed (* amp omega decay (math/cos phase)))
                   v (* 0.1 amp omega decay (math/cos phase))
                   w (* amp omega decay (math/sin phase))]
               {:u u :v v :w w})))))))))

(defn- debris-radial-field
  [{:keys [origin extent resolution]} {:keys [impulse roughness]
                                       :or {impulse 10.0 roughness 0.5}}]
  (let [{:keys [nx ny]} resolution
        {:keys [lx ly]} extent
        {:keys [x y]} origin
        dx (/ lx (max 1 (dec nx)))
        dy (/ ly (max 1 (dec ny)))
        cx (+ x (/ lx 2.0))
        cy (+ y (/ ly 2.0))]
    (vec
     (for [j (range ny)]
       (vec
        (for [i (range nx)]
          (let [px (+ x (* i dx))
                py (+ y (* j dy))
                rx (- px cx)
                ry (- py cy)
                r (math/sqrt (+ (* rx rx) (* ry ry) 1.0e-6))
                decay (math/exp (/ (* -1.0 r) (max roughness 0.1)))
                speed (* impulse decay (/ 1.0 (inc r)))]
            {:u (* speed (/ rx r))
             :v (* speed (/ ry r))
             :w 0.0})))))))

(defn- annotate
  [flow metadata]
  {:flow-field flow
   :metadata metadata})

(defn potential-flow
  [geometry {:keys [strength radius center] :as params}]
  (let [geometry (ensure-cartesian! (cfd-core/validate-geometry! geometry))
        center* (merge {:cx (+ (:x (:origin geometry)) (/ (:lx (:extent geometry)) 2.0))
                        :cy (+ (:y (:origin geometry)) (/ (:ly (:extent geometry)) 2.0))}
                       center)
        flow (gaussian-vortex geometry {:strength (or strength 5.0)
                                        :radius (max 1.0 (or radius 10.0))
                                        :center center*})]
    (annotate
     {:velocity flow
      :pressure nil
      :mode :airflow}
     {:source :analytical-potential-flow
      :parameters {:strength strength :radius radius :center center*}})))

(defn plume
  [geometry environment {:keys [emission-rate stability] :as params}]
  (let [geometry (ensure-cartesian! (cfd-core/validate-geometry! geometry))
        geometry (do
                   (when (not= 3 (:dimensions geometry))
                     (throw (ex-info "Plume surrogate requires 3D geometry"
                                     {:dimensions (:dimensions geometry)})))
                   (let [nz (get-in geometry [:resolution :nz])
                         lz (get-in geometry [:extent :lz])
                         dz (get-in geometry [:spacing :dz])]
                     (when (or (nil? nz) (< nz 2))
                       (throw (ex-info "Plume surrogate requires :nz >= 2" {:resolution (:resolution geometry)})))
                     (when (or (nil? lz) (not (pos? lz)))
                       (throw (ex-info "Plume surrogate requires :extent :lz > 0" {:extent (:extent geometry)})))
                     (when (or (nil? dz) (not (pos? dz)))
                       (throw (ex-info "Plume surrogate requires :spacing :dz > 0" {:spacing (:spacing geometry)})))
                     geometry))
        environment (cfd-core/validate-environment! environment)
        wind (get-in environment [:wind :reference] {:x 1.0 :y 0.0 :z 0.0})
        u-mag (math/sqrt (+ (* (:x wind) (:x wind))
                            (* (:y wind) (:y wind))
                            (* (:z wind) (:z wind))))
        field (gaussian-plume geometry {:emission-rate (or emission-rate 1.0)
                                        :wind-speed (max u-mag 0.1)
                                        :stability (max 0.1 (or stability 0.5))})]
    (annotate
     {:scalar field
      :mode :plume}
     {:source :analytical-plume
      :parameters {:emission-rate emission-rate
                   :stability stability}})))

(defn maritime
  [geometry environment params]
  (let [geometry (ensure-cartesian! (cfd-core/validate-geometry! geometry))
        environment (cfd-core/validate-environment! environment)]
    (when (not= 3 (:dimensions geometry))
      (throw (ex-info "Maritime surrogate requires 3D geometry" {:dimensions (:dimensions geometry)})))
    (let [velocity (sinusoidal-wave-field geometry params)]
      (annotate {:velocity velocity
                 :mode :maritime}
                {:source :analytical-wave
                 :fluid (:fluid environment)
                 :parameters params}))))

(defn debris
  [geometry params]
  (let [geometry (ensure-cartesian! (cfd-core/validate-geometry! geometry))]
    (when (not= 2 (:dimensions geometry))
      (throw (ex-info "Debris surrogate requires 2D geometry" {:dimensions (:dimensions geometry)})))
    (let [velocity (debris-radial-field geometry params)]
      (annotate {:velocity velocity
                 :mode :debris}
                {:source :analytical-debris
                 :parameters params}))))

(defn custom-tensor
  [payload]
  (let [{:keys [velocity-tensor pressure-tensor provider model-id]} payload
        model-info (when model-id
                     (or (model-entry model-id)
                         (throw (ex-info "Unknown surrogate model id" {:model-id model-id}))))]
    (annotate {:velocity velocity-tensor
               :pressure pressure-tensor
               :mode :custom}
              {:source :ml
               :provider provider
               :model model-info
               :note "Output not yet validated by corrector"})))

(defn predict
  [{:keys [solver] :as opts}]
  (case solver
    :potential-flow (potential-flow (:geometry opts) (:parameters opts))
    :plume (plume (:geometry opts) (:environment opts) (:parameters opts))
    :maritime (maritime (:geometry opts) (:environment opts) (:parameters opts))
    :debris (debris (:geometry opts) (:parameters opts))
    :tensor (custom-tensor (:payload opts))
    (throw (ex-info "Unknown surrogate solver" {:solver solver :options opts}))))
