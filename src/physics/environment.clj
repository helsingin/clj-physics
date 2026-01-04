(ns physics.environment
  "Environmental models: atmosphere, ocean, gravity, and fluid properties."
  (:require [clojure.math :as math]))

(def ^:const gas-constant 287.05287)
(def ^:const gamma-air 1.4)
(def ^:const g0 9.80665)
(def ^:const sutherland-c1 1.458e-6)
(def ^:const sutherland-s 110.4)

(def lapse-layers
  "1976 US Standard Atmosphere layers up to 86 km."
  [{:h 0.0     :T 288.15 :P 101325.0   :L -0.0065}
   {:h 11000.0 :T 216.65 :P 22632.06   :L 0.0}
   {:h 20000.0 :T 216.65 :P 5474.889   :L 0.001}
   {:h 32000.0 :T 228.65 :P 868.019    :L 0.0028}
   {:h 47000.0 :T 270.65 :P 110.906    :L 0.0}
   {:h 51000.0 :T 270.65 :P 66.9389    :L -0.0028}
   {:h 71000.0 :T 214.65 :P 3.95639    :L -0.002}
   {:h 86000.0 :T 186.946 :P 0.3734    :L 0.0}])

(defn- select-layer [altitude]
  (last (filter #(<= (:h %) altitude) lapse-layers)))

(defn- compute-pressure [{:keys [h T P L]} altitude]
  (let [delta (- altitude h)]
    (if (zero? L)
      (* P (math/exp (/ (- (* g0 delta)) (* gas-constant T))))
      (let [term (/ (+ T (* L delta)) T)
            exponent (/ (- g0) (* L gas-constant))]
        (* P (math/pow term exponent))))))

(defn- compute-temperature [{:keys [T L h]} altitude]
  (+ T (* L (- altitude h))))

(defn air-viscosity
  "Dynamic viscosity via Sutherland's formula."
  [temperature]
  (/ (* sutherland-c1 (math/pow temperature 1.5))
     (+ temperature sutherland-s)))

(defn speed-of-sound [temperature]
  (math/sqrt (* gamma-air gas-constant temperature)))

(defn gravity
  "Local gravity magnitude as function of altitude."
  [altitude]
  (* g0 (math/pow (/ 6378137.0 (+ 6378137.0 altitude)) 2.0)))

(defn isa-profile
  "Return International Standard Atmosphere properties at ALTITUDE (meters).
  For altitudes above 86 km the last layer is extrapolated with constant
  temperature lapse rate (L = 0)."
  [altitude]
  (let [altitude (double (max 0.0 altitude))
        layer (select-layer altitude)
        layer (if layer
                layer
                (assoc (last lapse-layers) :h (last (map :h lapse-layers))))
        temperature (compute-temperature layer altitude)
        pressure (compute-pressure layer altitude)
        density (/ pressure (* gas-constant temperature))
        viscosity (air-viscosity temperature)
        a (speed-of-sound temperature)
        gravity (gravity altitude)]
    {:temperature-c (- temperature 273.15)
     :pressure-pa pressure
     :density-kg-per-m3 density
     :viscosity-pas viscosity
     :speed-of-sound-m-s a
     :gravity-m-s2 gravity}))

;; --- Ocean model ---

(defn- potential-temperature [depth]
  (let [depth (double depth)]
    (cond
      (<= depth 200.0) (- 20.0 (* 0.05 depth))
      (<= depth 1000.0) 10.0
      :else 4.0)))

(defn- seawater-density
  "UNESCO 1983 (EOS 80) density as function of temperature (°C), salinity (PSU)."
  [temperature salinity pressure]
  (let [t temperature
        s salinity
        rho-w (+ 999.842594
                 (* 6.793952e-2 t)
                 (* -9.09529e-3 (math/pow t 2))
                 (* 1.001685e-4 (math/pow t 3))
                 (* -1.120083e-6 (math/pow t 4))
                 (* 6.536332e-9 (math/pow t 5)))
        A (+ 0.824493
             (* -4.0899e-3 t)
             (* 7.6438e-5 (math/pow t 2))
             (* -8.2467e-7 (math/pow t 3))
             (* 5.3875e-9 (math/pow t 4)))
        B (+ -5.72466e-3
             (* 1.0227e-4 t)
             (* -1.6546e-6 (math/pow t 2)))
        C 4.8314e-4
        density0 (+ rho-w
                    (* A s)
                    (* B (math/pow s 1.5))
                    (* C (math/pow s 2)))
        bulk-modulus 2.15e9]
    (/ density0 (- 1.0 (/ pressure bulk-modulus)))))

(defn ocean-profile
  "Return seawater properties at given depth (m) and optional lat."
  [{:keys [depth-m lat-deg]
    :or {lat-deg 0.0}}]
  (let [depth (max 0.0 depth-m)
        temperature (potential-temperature depth)
        salinity (if (< (Math/abs lat-deg) 30.0) 35.0 34.0)
        pressure (+ 101325.0 (* g0 depth 1025.0))
        density (seawater-density temperature salinity pressure)]
    {:temperature-c temperature
     :salinity-psu salinity
     :pressure-pa pressure
     :density-kg-per-m3 density}))
