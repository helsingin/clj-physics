(ns physics.electromagnetics.constraints
  "Constraint evaluation for electromagnetics payloads."
  (:require [physics.electromagnetics.fields :as fields]))

(defn- violation [type value limit & {:as extras}]
  (merge {:type type
          :value value
          :limit limit
          :severity (:severity extras :critical)}
         (dissoc extras :severity)))

(defn evaluate-power-density
  "Check that the time-averaged power density does not exceed MAX-W-PER-M2.
  Returns a seq of violation maps enriched with the offending field."
  [field material {:keys [max-w-per-m2 severity] :or {severity :critical}}]
  (let [{:keys [value valid? flags]} (fields/power-density field material)
        data {:flags flags
              :field field
              :material (:material/name material)}]
    (cond
      (not valid?)
      [(violation {:constraint :field/power-density}
                  :indeterminate
                  "Unable to determine power density (non-finite impedance)"
                  (assoc data :value value))]

      (and max-w-per-m2 (> value max-w-per-m2))
      [(violation {:constraint :field/power-density}
                  :limit-exceeded
                  (format "Power density %.3f W/m^2 exceeds ceiling %.3f W/m^2"
                          value max-w-per-m2)
                  (assoc data :value value :limit max-w-per-m2 :severity severity))]

      :else [])))

(defn evaluate-field-amplitude
  "Check amplitude limits (e.g. safety or hardware limits)."
  [field {:keys [limit severity] :or {severity :critical}}]
  (let [amp (:field/amplitude field)]
    (if (and limit (> amp limit))
      [(violation (if (= :magnetic (:field/type field))
                    :h-field
                    :e-field)
                  amp
                  limit
                  :severity severity
                  :field field)]
      [])))
