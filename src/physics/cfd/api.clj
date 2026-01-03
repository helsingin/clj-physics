(ns physics.cfd.api
  "Unified entry point for AI-accelerated CFD predictions.  Translates high-level mode requests
   into surrogate + corrector pipelines and always returns a canonical structure:
     {:flow-field … :constraints … :confidence … :metadata {:mode … …}}"
  (:require
   [physics.cfd.core :as core]
   [physics.cfd.surrogate :as surrogate]
   [physics.cfd.corrector :as corrector]
   [physics.cfd.plume :as plume]
   [physics.cfd.maritime :as maritime]
   [physics.cfd.debris :as debris]))

(set! *warn-on-reflection* true)

(defn- normalize
  "Ensure every mode returns the shared contract."
  [{:keys [flow-field residuals confidence metadata]} mode]
  {:flow-field flow-field
   :constraints (or residuals {})
   :confidence (double (or confidence 0.0))
   :metadata (merge {:mode mode} (or metadata {}))})

(defn- airflow
  [{:keys [geometry parameters corrector-options]}]
  (let [geom (core/validate-geometry! geometry)
        {:keys [flow-field metadata]}
        (surrogate/predict {:solver :potential-flow
                            :geometry geom
                            :parameters parameters})
        result (corrector/correct {:geometry geom}
                                  flow-field
                                  corrector-options)]
    (normalize (assoc result :metadata metadata) :airflow)))

(defn- plume-mode
  [{:keys [geometry environment parameters corrector-options]}]
  (let [request (cond-> {:parameters parameters
                         :corrector-options corrector-options}
                  geometry (assoc :geometry geometry)
                  environment (assoc :environment environment))
        result (plume/predict request)]
    (normalize result :plume)))

(defn- maritime-mode
  [{:keys [geometry environment parameters corrector-options]}]
  (let [request (cond-> {:parameters parameters
                         :corrector-options corrector-options}
                  geometry (assoc :geometry geometry)
                  environment (assoc :environment environment))
        result (maritime/predict request)]
    (normalize result :maritime)))

(defn- debris-mode
  [{:keys [geometry environment parameters corrector-options]}]
  (let [request (cond-> {:parameters parameters
                         :corrector-options corrector-options}
                  geometry (assoc :geometry geometry)
                  environment (assoc :environment environment))
        result (debris/predict request)]
    (normalize result :debris)))

(defn predict-flow
  "Route a CFD request based on :mode and return { :flow-field … :constraints … :confidence … }.

   Options map:
     {:mode :airflow|:plume|:maritime|:debris
      :geometry …
      :environment …
      :parameters …
      :corrector-options {:iterations … :energy-limit …}}"
  [{:keys [mode] :as request}]
  (case mode
    :airflow (airflow request)
    :plume (plume-mode request)
    :maritime (maritime-mode request)
    :debris (debris-mode request)
    (throw (ex-info "Unsupported CFD mode" {:mode mode :request request}))))
