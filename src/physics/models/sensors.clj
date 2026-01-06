(ns physics.models.sensors
  "Standard sensor models for synthetic data generation.
   Includes Radar (SNR-based) and GPS (Drift-based)."
  (:require [physics.core :as core]
            [clojure.math :as math]))

(defn- gaussian-noise [std-dev]
  ;; Box-Muller transform
  (let [u1 (rand)
        u2 (rand)]
    (* std-dev
       (math/sqrt (* -2.0 (math/log u1)))
       (math/cos (* 2.0 Math/PI u2)))))

(defn radar-detect?
  "Check detection probability based on Radar Range Equation.
   rcs: Radar Cross Section (m^2).
   range: Distance (m).
   constants: {:transmit-power :gain :wavelength ...}"
  [range rcs {:keys [power gain wavelength noise-power] :or {noise-power 1e-12}}]
  (let [num (* power gain gain wavelength wavelength rcs)
        den (* (math/pow (* 4.0 Math/PI) 3) (math/pow range 4) noise-power)
        snr (if (zero? den) 0.0 (/ num den))
        ;; Simple Swerling 0 model (steady target)
        ;; P_d approx Q(sqrt(2*SNR), sqrt(-2*ln(P_fa))) ... simplified logistic
        p-det (/ 1.0 (+ 1.0 (math/exp (- (- (* 10.0 (math/log10 snr)) 10.0)))))] ;; Sigmoid centered at 10dB
    (< (rand) p-det)))

(defn gps-measurement
  "Generate noisy GPS reading.
   drift: systematic bias (Random Walk).
   noise: white noise."
  [true-pos {:keys [drift noise-sigma] :or {drift [0 0 0] noise-sigma 5.0}}]
  (mapv + true-pos drift [(gaussian-noise noise-sigma)
                          (gaussian-noise noise-sigma)
                          (gaussian-noise noise-sigma)]))
