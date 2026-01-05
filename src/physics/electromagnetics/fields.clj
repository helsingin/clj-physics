(ns physics.electromagnetics.fields
  "Field descriptors and deterministic helpers (phasor convention e^{+jωt})."
  (:require [clojure.math :as math]
            [physics.core :as pcore]
            [physics.electromagnetics.materials :as materials]
            [physics.electromagnetics.math :as emath]
            [malli.core :as m]
            [malli.error :as me]))

(defn- validate!
  [schema value label]
  (if (m/validate schema value)
    value
    (throw (ex-info (str "Invalid " label)
                    {:errors (me/humanize (m/explain schema value))
                     :value value
                     :schema schema}))))

(def ^:private orientation-tolerance-rad 1.0e-3)

(def FieldDescriptor
  [:map
   [:type [:enum :electric :magnetic]]
   [:frequency-hz number?]
   [:amplitude number?]
   [:orientation {:optional true} [:vector {:min 3 :max 3} number?]]
   [:phase-deg {:optional true} number?]])

(defn- wrap-phase [deg]
  (let [wrapped (mod deg 360.0)]
    (if (neg? wrapped) (+ wrapped 360.0) wrapped)))

(defn- normalise-distribution [value]
  (cond
    (map? value)
    (let [{:keys [type mean sd min max sigma]} value]
      {:type (or type (if (and min max) :uniform :gaussian))
       :mean (or mean (:value value) (:mean value) (:center value) (:mid value))
       :sd (or sd sigma (:sd value))
       :min min
       :max max})
    :else nil))

(defn ->field
  "Normalise a field descriptor. Keys (keywords) are namespaced with :field/.
   Supports amplitude/phase distributions for stochastic modelling."
  [{:keys [type frequency-hz amplitude orientation phase-deg polarization meta amplitude-distribution phase-distribution]
    :as descriptor
    :or {type :electric
         frequency-hz 0.0
         amplitude 0.0
         orientation [0.0 0.0 1.0]
         phase-deg 0.0}}]
  (let [_ (when-not (or amplitude-distribution
                        phase-distribution
                        (map? amplitude)
                        (map? phase-deg))
            ;; Only validate basic scalar fields strictly; distributions are looser
            (validate! FieldDescriptor descriptor "field descriptor"))
        orientation (pcore/normalize orientation)
        amp-dist (or amplitude-distribution (normalise-distribution amplitude))
        amp (if (map? amplitude)
              (or (:mean amplitude) (:value amplitude) (:mid amplitude) 0.0)
              amplitude)
        phase-dist (or phase-distribution (normalise-distribution phase-deg))
        phase (if (map? phase-deg) (or (:mean phase-deg) (:value phase-deg) 0.0) phase-deg)
        amp-db (if (pos? amp) (* 20.0 (math/log10 amp)) Double/NEGATIVE_INFINITY)]
    {:field/type type
     :field/frequency-hz (double frequency-hz)
     :field/amplitude (double amp)
     :field/amplitude-db amp-db
     :field/amplitude-distribution amp-dist
     :field/orientation orientation
     :field/phase-deg (wrap-phase phase)
     :field/phase-distribution phase-dist
     :field/polarization polarization
     :field/meta meta}))

(defn- ensure-common-orientation [fields]
  (when-let [orientation (:field/orientation (first fields))]
    (doseq [f (rest fields)]
      (let [dot (pcore/dot orientation (:field/orientation f))
            dot (pcore/clamp dot -1.0 1.0)
            angle (Math/acos dot)]
        (when (> angle orientation-tolerance-rad)
          (throw (ex-info "Field orientations must be co-linear for superposition"
                          {:expected orientation
                           :found (:field/orientation f)
                           :angle-rad angle}))))))
  fields)

(defn- ensure-common-frequency [fields]
  (when-let [freq (:field/frequency-hz (first fields))]
    (doseq [f (rest fields)]
      (when (not= freq (:field/frequency-hz f))
        (throw (ex-info "Field frequencies must match for phasor superposition"
                        {:expected freq
                         :found (:field/frequency-hz f)})))))
  (:field/frequency-hz (first fields)))

(defn- ensure-common-polarization [fields]
  (let [pol (:field/polarization (first fields))]
    (when (and pol
               (some #(not= pol (:field/polarization %)) (rest fields)))
      (throw (ex-info "Field polarizations must match for phasor superposition"
                      {:expected pol}))))
  (:field/polarization (first fields)))

(defn superpose
  "Phasor sum of co-linear harmonic fields.
  Returns a new field map; frequency/type are derived from the first element."
  [fields]
  (if (empty? fields)
    (->field {})
    (let [fields (ensure-common-orientation fields)
          freq (ensure-common-frequency fields)
          orientation (:field/orientation (first fields))
          type (:field/type (first fields))
          polarization (ensure-common-polarization fields)
          {:keys [re im]}
          (reduce
           (fn [{acc-re :re acc-im :im} {:field/keys [amplitude phase-deg]}]
             (let [rad (math/to-radians phase-deg)]
               {:re (+ acc-re (* amplitude (math/cos rad)))
                :im (+ acc-im (* amplitude (math/sin rad)))}))
           {:re 0.0 :im 0.0}
           fields)
          amplitude (math/sqrt (+ (* re re) (* im im)))
          phase (wrap-phase (math/to-degrees (math/atan2 im re)))
          metas (into [] (keep :field/meta fields))]
      {:field/type type
       :field/frequency-hz freq
       :field/amplitude amplitude
       :field/amplitude-db (if (pos? amplitude) (* 20.0 (math/log10 amplitude)) Double/NEGATIVE_INFINITY)
       :field/orientation orientation
       :field/phase-deg phase
       :field/polarization polarization
       :field/meta (when (seq metas) metas)})))

(defn power-density
  "Time-averaged Poynting vector magnitude (W/m^2) for a plane wave.
  Returns {:value .. :units :w-per-m2 :valid? bool :flags #{..} :impedance eta}."
  [field material]
  (let [amp (:field/amplitude field)
        freq (:field/frequency-hz field)
        eta (materials/intrinsic-impedance material {:frequency-hz freq})
        eta-mag-sq (when eta (emath/magnitude-squared eta))
        type (:field/type field)
        valid? (and (:valid? eta) (pos? eta-mag-sq))
        flags (:flags eta)
        value (cond
                (not valid?) 0.0
                (= type :magnetic)
                (* 0.5 (:re eta) amp amp)
                :else
                (let [re-eta (:re eta)]
                  (* 0.5 amp amp (/ re-eta eta-mag-sq))))]
    {:value value
     :units :w-per-m2
     :valid? valid?
     :flags flags
     :impedance eta}))

(defn- sample-gaussian [rng {:keys [mean sd]}]
  (let [sd (or sd 0.0)]
    (+ mean (* sd (.nextGaussian rng)))))

(defn- sample-uniform [rng {:keys [min max]}]
  (let [min (double min)
        max (double max)]
    (+ min (* (.nextDouble rng) (- max min)))))

(defn sample-field
  "Draw a stochastic sample from a field distribution."
  ([field] (sample-field field (java.util.Random.)))
  ([field ^java.util.Random rng]
   (let [amp-dist (:field/amplitude-distribution field)
         phase-dist (:field/phase-distribution field)
         amplitude (cond
                     (nil? amp-dist) (:field/amplitude field)
                     (= (:type amp-dist) :uniform) (sample-uniform rng amp-dist)
                     :else (sample-gaussian rng (assoc amp-dist :mean (:mean amp-dist))))
         phase (cond
                 (nil? phase-dist) (:field/phase-deg field)
                 (= (:type phase-dist) :uniform) (sample-uniform rng phase-dist)
                 :else (sample-gaussian rng (assoc phase-dist :mean (:mean phase-dist))))
         sampled (->field {:type (:field/type field)
                           :frequency-hz (:field/frequency-hz field)
                           :amplitude amplitude
                           :orientation (:field/orientation field)
                           :phase-deg phase
                           :polarization (:field/polarization field)
                           :meta (:field/meta field)
                           :amplitude-distribution (:field/amplitude-distribution field)
                           :phase-distribution (:field/phase-distribution field)})]
     (assoc sampled
            :field/source field))))
