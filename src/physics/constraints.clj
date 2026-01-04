(ns physics.constraints
  "Operational envelope validation for physics models."
  (:require [clojure.math :as math]))

(defn- violation [type value limit severity]
  {:type type
   :value value
  :limit limit
   :severity severity})

(defn evaluate-envelope
  "Check aerodynamic flight envelope limits for an airframe model.
  Returns a seq of violation maps."
  [model {:keys [mach load-factor-g bank-deg alpha-rad]}]
  (let [{limit-mach :mach-limit
         g-limits :load-factor-g
         bank-limit :bank-deg
         aoa-limit :alpha-rad} (:limits model)
        violations (transient [])
        mach-val (or mach 0.0)
        load-val (or load-factor-g 0.0)
        bank-val (Math/abs (or bank-deg 0.0))
        aoa-val (or alpha-rad 0.0)]
    (when (and limit-mach (> mach-val limit-mach))
      (conj! violations (violation :overspeed mach-val limit-mach :critical)))
    (when (and g-limits (> load-val (:max g-limits)))
      (conj! violations (violation :over-g load-val (:max g-limits) :critical)))
    (when (and g-limits (< load-val (:min g-limits)))
      (conj! violations (violation :under-g load-val (:min g-limits) :warning)))
    (let [bank-limit (or bank-limit 90.0)]
      (when (> bank-val bank-limit)
        (conj! violations (violation :over-bank bank-val bank-limit :warning))))
    (when aoa-limit
      (let [amax (:max aoa-limit)
            amin (:min aoa-limit)]
        (when (and amax (> aoa-val amax))
          (conj! violations (violation :stall aoa-val amax :critical)))
        (when (and amin (< aoa-val amin))
          (conj! violations (violation :reverse-stall aoa-val amin :warning)))))
    (persistent! violations)))

(defn evaluate-depth
  "Check sub-surface platform depth limits."
  [model {:keys [depth-m]}]
  (let [limit (get-in model [:limits :max-depth-m])
        violations (transient [])]
    (when (and limit (>= (or depth-m 0.0) limit))
      (conj! violations (violation :over-depth depth-m limit :critical)))
    (persistent! violations)))
