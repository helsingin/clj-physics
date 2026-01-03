(ns physics.electromagnetics.propagation
  "Plane-wave propagation helpers for homogeneous media (phasor convention e^{+jωt})."
  (:require [clojure.math :as math]
            [physics.electromagnetics.materials :as materials]
            [physics.electromagnetics.math :as emath]
            [physics.electromagnetics.fields :as fields]))

(defn- propagation-constant
  [material frequency-hz]
  (let [ε (materials/absolute-permittivity material)
        μ (materials/absolute-permeability material)
        σ (:material/conductivity material)
        ω (* 2.0 math/PI frequency-hz)]
    (when (zero? ω)
      (throw (ex-info "Propagation constant undefined for zero frequency"
                      {:material (:material/name material)
                       :frequency frequency-hz
                       :conductivity σ})))
    (let [ωc (emath/complex ω 0.0)
          μc (emath/complex μ 0.0)
          sigma (emath/complex σ 0.0)
          factor (emath/c+ sigma (emath/c* emath/j (emath/c* ωc ε)))
          jwμ (emath/c* emath/j (emath/c* ωc μc))]
      (emath/csqrt (emath/c* factor jwμ)))))

(defn- wrap-phase [deg]
  (let [wrapped (mod deg 360.0)]
    (if (neg? wrapped) (+ wrapped 360.0) wrapped)))

(defn health-metrics
  "Return diagnostic metrics for propagation variables."
  [{:keys [gamma eta epsilon mu]}]
  (let [gamma-mag (emath/magnitude gamma)
        eta-mag (when (map? eta) (emath/magnitude eta))
        cond-num (if (and gamma-mag eta-mag (pos? gamma-mag) (pos? eta-mag))
                   (/ (max gamma-mag eta-mag) (min gamma-mag eta-mag))
                   Double/NaN)
        epsilon-mag (when (map? epsilon) (emath/magnitude epsilon))
        mu-mag (if (map? mu) (emath/magnitude mu) (Math/abs (double mu)))]
    {:gamma-magnitude gamma-mag
     :eta-magnitude eta-mag
     :epsilon-magnitude epsilon-mag
     :mu-magnitude mu-mag
     :condition cond-num
     :underflow? (< gamma-mag 1e-300)
     :overflow? (> gamma-mag 1e300)
     :valid? (and (:valid? gamma)
                 (if (map? eta) (:valid? eta) true)
                 (if (map? epsilon) (:valid? epsilon) true)
                 (if (map? mu) (:valid? mu) true))}))

(defn attenuation-constant
  "Return attenuation constant α (Np/m)."
  [material frequency-hz]
  (:re (propagation-constant material frequency-hz)))

(defn phase-constant
  "Return phase constant β (rad/m)."
  [material frequency-hz]
  (:im (propagation-constant material frequency-hz)))

(defn propagate-plane-wave
  "Propagate FIELD through MATERIAL over DISTANCE meters.
  Returns {:field updated-field
           :metrics {:attenuation-db ..        ; total loss
                     :attenuation-db-per-m ..  ; per-metre loss
                     :phase-shift-deg ..
                     :gamma gamma
                     :health {...}}}."
  [field material distance]
  (let [freq (:field/frequency-hz field)
        gamma (propagation-constant material freq)
        α (:re gamma)
        β (:im gamma)
        path (max 0.0 (double distance))
        atten (math/exp (* -1.0 α path))
        loss-db (* 8.686 α path)
        phase-shift (* β distance)
        new-phase (wrap-phase (+ (:field/phase-deg field)
                                 (math/to-degrees phase-shift)))
        updated (-> field
                    (assoc :field/amplitude (* (:field/amplitude field) atten))
                    (assoc :field/amplitude-db
                           (if (> atten 0.0)
                             (+ (:field/amplitude-db field) (* 20.0 (math/log10 atten)))
                             Double/NEGATIVE_INFINITY))
                    (assoc :field/phase-deg new-phase))
        eta (materials/intrinsic-impedance material {:frequency-hz freq})
        epsilon (materials/absolute-permittivity material)
        mu (materials/absolute-permeability material)
        health (health-metrics {:gamma gamma
                                :eta eta
                                :epsilon epsilon
                                :mu mu})]
    {:field updated
     :metrics {:attenuation-db loss-db
               :attenuation-db-per-m (* 8.686 α)
               :phase-shift-deg (math/to-degrees phase-shift)
               :gamma gamma
               :health health}}))

(defn- summary
  [values]
  (let [n (count values)
        mean (/ (reduce + values) n)
        variance (/ (reduce (fn [acc x]
                              (let [diff (- x mean)]
                                (+ acc (* diff diff))))
                            0.0
                            values)
                    (max 1 (- n 1)))]
    {:mean mean
     :stddev (math/sqrt variance)}))

(defn propagate-monte-carlo
  "Monte-Carlo propagation returning statistics for amplitude/metrics.
   Options: {:samples n :rng rng :material-sampler f :field-sampler f}."
  ([field material distance]
   (propagate-monte-carlo field material distance {:samples 100}))
  ([field material distance {:keys [samples rng field-sampler material-sampler]
                             :or {samples 100}}]
   (let [rng (or rng (java.util.Random.))
         sample-field-fn (or field-sampler #(fields/sample-field % rng))
         sample-material-fn
         (or material-sampler
             (fn [mat ^java.util.Random r]
               (letfn [(draw [dist fallback]
                         (if-not dist
                           fallback
                           (case (:type dist)
                             :uniform (+ (:min dist)
                                         (* (.nextDouble r)
                                            (- (:max dist) (:min dist))))
                             :gaussian (+ (:mean dist)
                                          (* (get dist :sd 0.0)
                                             (.nextGaussian r)))
                             fallback)))]
                 (let [sigma-dist (:material/sigma-distribution mat)
                       perm-dist (:material/permittivity-distribution mat)
                       cond (draw sigma-dist (:material/conductivity mat))
                       perm (draw perm-dist (:material/permittivity-relative mat))
                       base (materials/->material {:name (:material/name mat)
                                                   :type (:material/type mat)
                                                   :permittivity-relative perm
                                                   :permeability-relative (:material/permeability-relative mat)
                                                   :conductivity cond
                                                   :loss-tangent (:material/loss-tangent mat)
                                                   :tunable (:material/tunable mat)})]
                   (assoc base
                          :material/source mat
                          :material/sigma-distribution sigma-dist
                          :material/permittivity-distribution perm-dist))))
             )
         runs (repeatedly samples
                          (fn []
                            (let [sampled-field (sample-field-fn field)
                                  sampled-material (sample-material-fn material rng)
                                  result (propagate-plane-wave sampled-field sampled-material distance)]
                              {:result result
                               :field sampled-field
                               :material sampled-material})))
         amplitudes (map (comp :field/amplitude :field) runs)
         attenuation (map (comp (fn [m] (get-in m [:metrics :attenuation-db])) :result) runs)
         attenuation-per-m (map (comp (fn [m] (get-in m [:metrics :attenuation-db-per-m])) :result) runs)
         phase (map (comp (fn [m] (get-in m [:metrics :phase-shift-deg])) :result) runs)
         valid-count (count (filter (fn [run] (true? (get-in run [:result :metrics :health :valid?])))
                                    runs))]
     {:samples samples
      :valid-samples valid-count
      :amplitude (summary amplitudes)
      :attenuation-db (summary attenuation)
      :attenuation-db-per-m (summary attenuation-per-m)
      :phase-deg (summary phase)
      :runs runs})
    ))
