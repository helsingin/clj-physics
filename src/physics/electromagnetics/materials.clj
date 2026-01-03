(ns physics.electromagnetics.materials
  "Material descriptors and derived quantities for electromagnetic modelling."
  (:require [clojure.math :as math]
            [physics.core :as pcore]
            [physics.electromagnetics.math :as emath]))

(def ^:const epsilon0 8.854187817e-12)
(def ^:const mu0 (* 4.0 math/PI 1e-7))
(def ^:const c0 299792458.0)

(def vacuum
  "Reference material for free space."
  {:material/name "vacuum"
   :material/type :dielectric
   :material/permittivity-relative 1.0
   :material/permeability-relative 1.0
   :material/conductivity 0.0
   :material/loss-tangent 0.0})

(defn- normalise-dist [value]
  (cond
    (map? value)
    (let [{:keys [type mean sd min max sigma]} value]
      {:type (or type (if (and min max) :uniform :gaussian))
       :mean (or mean (:value value) (:mean value) (:center value) (:mid value))
       :sd (or sd sigma (:sd value))
       :min min
       :max max})
    :else nil))

(defn ->material
  "Normalise a material description map. Supports optional tunable parameters:
  {:bias-range [min max]
   :permittivity-range [eps-min eps-max]
   :permeability-range [mu-min mu-max]}.

Phasor convention: e^{+jωt}."
  [{:keys [name type permittivity-relative permeability-relative conductivity loss-tangent tunable
           conductivity-distribution permittivity-distribution]
    :or {type :dielectric
         permittivity-relative 1.0
         permeability-relative 1.0
         conductivity 0.0
         loss-tangent 0.0}}]
  (let [perm-value (if (map? permittivity-relative)
                     (or (:mean permittivity-relative)
                         (:value permittivity-relative)
                         (:mid permittivity-relative)
                         1.0)
                     permittivity-relative)
        cond-value (if (map? conductivity)
                     (or (:mean conductivity)
                         (:value conductivity)
                         (:mid conductivity)
                         0.0)
                     conductivity)]
    {:material/name name
     :material/type type
     :material/permittivity-relative (double perm-value)
     :material/permeability-relative (double permeability-relative)
     :material/conductivity (double cond-value)
     :material/loss-tangent (double loss-tangent)
     :material/tunable tunable
     :material/sigma-distribution (or conductivity-distribution (normalise-dist conductivity))
     :material/permittivity-distribution (or permittivity-distribution (normalise-dist permittivity-relative))}))

(defn- interpolate [range bias]
  (let [[lo hi] range]
    (+ lo (* (- hi lo) bias))))

(defn- normalise-bias [{:keys [bias-range on-clamp]} bias]
  (let [[bmin bmax] (or bias-range [0.0 1.0])]
    (cond
      (nil? bias) nil
      (<= bmin bmax)
      (let [raw (/ (- bias bmin) (max 1e-12 (- bmax bmin)))
            clamped (pcore/clamp raw 0.0 1.0)]
        (when (and on-clamp (not= raw clamped))
          (on-clamp {:bias bias
                     :range [bmin bmax]
                     :normalised clamped}))
        clamped)
      :else nil)))

(defn relative-permittivity
  "Return relative permittivity εr, applying optional bias for tunable materials."
  ([material] (emath/complex (:material/permittivity-relative material) 0.0))
  ([material {:keys [bias on-clamp]}]
   (let [{:keys [permittivity-range]} (:material/tunable material)
         εr (:material/permittivity-relative material)
         tanδ (:material/loss-tangent material)
         base (if (and permittivity-range bias)
                (interpolate permittivity-range (normalise-bias (assoc (:material/tunable material) :on-clamp on-clamp) bias))
                εr)]
     (if (zero? tanδ)
       (emath/complex base 0.0)
       (emath/c* (emath/complex base 0.0)
                 (emath/complex 1.0 (- tanδ)))))))

(defn relative-permeability
  "Return relative permeability μr, applying optional bias for tunable materials."
  ([material] (:material/permeability-relative material))
  ([material {:keys [bias on-clamp]}]
   (let [{:keys [permeability-range]} (:material/tunable material)
         μr (:material/permeability-relative material)]
     (if (and permeability-range bias)
       (interpolate permeability-range (normalise-bias (assoc (:material/tunable material) :on-clamp on-clamp) bias))
       μr))))

(defn absolute-permittivity
  ([material]
   (emath/c* (emath/complex epsilon0 0.0) (relative-permittivity material)))
  ([material opts]
   (emath/c* (emath/complex epsilon0 0.0) (relative-permittivity material opts))))

(defn absolute-permeability
  (^double [material]
   (* mu0 (relative-permeability material)))
  (^double [material opts]
   (* mu0 (relative-permeability material opts))))

(defn intrinsic-impedance
  "Intrinsic impedance (complex) for MATERIAL at FREQUENCY-HZ, accounting for conductivity.
  See Pozar, Microwave Engineering (4e), eq. 2.44. Phasor convention: e^{+jωt}."
  ([material] (intrinsic-impedance material {:frequency-hz 0.0}))
  ([material {:keys [frequency-hz bias]
              :or {frequency-hz 0.0}}]
   (let [ε (absolute-permittivity material {:bias bias})
         μ (absolute-permeability material {:bias bias})
         σ (:material/conductivity material)
         ω (* 2.0 math/PI frequency-hz)]
     (cond
       (zero? ω)
       (if (and (zero? σ) (zero? (:im ε)))
         (emath/complex (math/sqrt (/ μ (:re ε))) 0.0)
         (throw (ex-info "Intrinsic impedance undefined for zero frequency in lossy or conductive media"
                         {:material (:material/name material)
                          :frequency frequency-hz
                          :conductivity σ
                          :loss-tangent (:material/loss-tangent material)})))
       :else
       (let [ωc (emath/complex ω 0.0)
             μc (emath/complex μ 0.0)
             numerator (emath/c* emath/j (emath/c* ωc μc))
             denom (emath/c+ (emath/complex σ 0.0)
                             (emath/c* emath/j (emath/c* ωc ε)))]
         (emath/csqrt (emath/cdiv numerator denom)))))))
