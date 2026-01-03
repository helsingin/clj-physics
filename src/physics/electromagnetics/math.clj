(ns physics.electromagnetics.math
  "Utility helpers for electromagnetics: robust complex arithmetic with resilience metadata."
  (:require [clojure.math :as math]
            [clojure.set :as set])
  (:import (java.math BigDecimal MathContext RoundingMode)))

(def ^:dynamic *condition-threshold*
  "Condition number above which high-precision fallbacks are attempted."
  1.0e12)

(def ^:dynamic *scale-threshold*
  "Magnitude order at which scaling warnings are emitted."
  300.0)

(def ^:dynamic *validity-log*
  "Optional atom/vector for collecting diagnostic maps from arithmetic operations."
  nil)

(def ^MathContext high-precision-mc (MathContext. 50 RoundingMode/HALF_EVEN))

(defn- hypot-large
  [a b]
  (Math/hypot a b))

(defn magnitude
  "Return magnitude of a robust complex number."
  [z]
  (hypot-large (:re z) (:im z)))

(defn magnitude-squared [z]
  (+ (* (:re z) (:re z)) (* (:im z) (:im z))))

(defn phase [z]
  (math/atan2 (:im z) (:re z)))

(defn- record-diagnostic! [info]
  (when *validity-log*
    (swap! *validity-log* conj info)))

(defn- log10-magnitude [re im]
  (let [mag (hypot-large re im)]
    (if (zero? mag)
      Double/NEGATIVE_INFINITY
      (math/log10 mag))))

(defn- merge-flags [& flags]
  (reduce set/union #{} (map #(cond
                                (nil? %) #{}
                                (set? %) %
                                :else #{%})
                             flags)))

(defn- mag-order [z]
  (if (contains? z :mag-order)
    (:mag-order z)
    (log10-magnitude (:re z) (:im z))))

(defn- combine-inputs [& zs]
  (let [valid? (every? #(get % :valid? true) zs)
        flags (apply merge-flags (map :flags zs))
        flags (cond-> flags (not valid?) (conj :invalid-input))]
    {:valid? valid?
     :flags flags}))

(defn complex
  "Create a robust complex number map.
   Options: :flags set of diagnostic keywords, :valid? boolean, :op keyword, :metadata map."
  ([re im] (complex re im {}))
  ([re im {:keys [flags valid? op metadata]
           :or {flags #{}
                valid? true
                op :literal
                metadata {}}}]
   (let [flags (set flags)
         finite? (and (Double/isFinite re) (Double/isFinite im))
         mag-order (log10-magnitude re im)
         flags (cond-> flags
                 (not finite?) (conj :non-finite)
                 (> (Math/abs mag-order) *scale-threshold*) (conj :scale-extreme))
         valid? (and valid? finite?)]
     (when (and *validity-log*
                (or (not valid?)
                    (some flags #{:scale-extreme :condition-high :division-by-zero})))
       (record-diagnostic! (merge {:op op
                                   :re re
                                   :im im
                                   :flags flags
                                   :valid? valid?
                                   :mag-order mag-order}
                                  metadata)))
     {:re (double re)
      :im (double im)
      :mag-order mag-order
      :valid? valid?
      :flags flags})))

(defn c+
  [a b]
  (let [{:keys [flags valid?]} (combine-inputs a b)
        re (+ (:re a) (:re b))
        im (+ (:im a) (:im b))]
    (complex re im {:flags flags :valid? valid? :op :add})))

(defn c-
  [a b]
  (let [{:keys [flags valid?]} (combine-inputs a b)
        re (- (:re a) (:re b))
        im (- (:im a) (:im b))]
    (complex re im {:flags flags :valid? valid? :op :sub})))

(defn c*
  [a b]
  (let [{:keys [flags valid?]} (combine-inputs a b)
        order-sum (+ (Math/abs ^double (mag-order a))
                     (Math/abs ^double (mag-order b)))
        high-precision? (> order-sum *scale-threshold*)
        flags (cond-> flags high-precision? (conj :scale-extreme))]
    (if high-precision?
      (let [are (BigDecimal/valueOf (:re a))
            aim (BigDecimal/valueOf (:im a))
            bre (BigDecimal/valueOf (:re b))
            bim (BigDecimal/valueOf (:im b))
            re (.subtract (.multiply are bre high-precision-mc)
                          (.multiply aim bim high-precision-mc) high-precision-mc)
            im (.add (.multiply are bim high-precision-mc)
                     (.multiply aim bre high-precision-mc) high-precision-mc)]
        (record-diagnostic! {:op :mul :mode :high-precision :flags flags})
        (complex (.doubleValue re) (.doubleValue im)
                 {:flags flags
                  :valid? valid?
                  :op :mul
                  :metadata {:mode :high-precision}}))
      (let [re (- (* (:re a) (:re b)) (* (:im a) (:im b)))
            im (+ (* (:re a) (:im b)) (* (:im a) (:re b)))]
        (complex re im {:flags flags :valid? valid? :op :mul})))))

(defn cdiv
  "Divide complex numbers A/B using Smith's algorithm for numerical stability."
  [a b]
  (let [abr (Math/abs (:re b))
        abi (Math/abs (:im b))
        {:keys [flags valid?]} (combine-inputs a b)
        condition (/ (max abr abi) (max 1e-300 (min abr abi)))
        high-precision? (> condition *condition-threshold*)
        flags (cond-> flags high-precision? (conj :condition-high))]
    (when high-precision?
      (record-diagnostic! {:op :cdiv :condition condition :flags flags}))
    (cond
      (and (zero? abr) (zero? abi))
      (complex 0.0 0.0 {:flags (conj flags :division-by-zero)
                         :valid? false
                         :op :div})

      high-precision?
      (let [are (BigDecimal/valueOf (:re a))
            aim (BigDecimal/valueOf (:im a))
            bre (BigDecimal/valueOf (:re b))
            bim (BigDecimal/valueOf (:im b))
            denom (.add (.multiply bre bre high-precision-mc)
                        (.multiply bim bim high-precision-mc) high-precision-mc)]
        (if (zero? (.doubleValue denom))
            (complex 0.0 0.0 {:flags (conj flags :division-by-zero)
                               :valid? false
                               :op :div
                               :metadata {:condition condition :mode :high-precision}})
          (let [re (.divide (.add (.multiply are bre high-precision-mc)
                                  (.multiply aim bim high-precision-mc) high-precision-mc)
                            denom high-precision-mc)
                im (.divide (.subtract (.multiply aim bre high-precision-mc)
                                       (.multiply are bim high-precision-mc) high-precision-mc)
                            denom high-precision-mc)]
            (complex (.doubleValue re) (.doubleValue im)
                     {:flags flags
                      :valid? valid?
                      :op :div
                      :metadata {:condition condition :mode :high-precision}}))))

      (>= abr abi)
      (let [ratio (if (zero? (:re b)) 0.0 (/ (:im b) (:re b)))
            denom (+ (:re b) (* (:im b) ratio))]
        (if (zero? denom)
          (complex 0.0 0.0 {:flags (conj flags :division-by-zero)
                             :valid? false
                             :op :div})
          (complex (/ (+ (:re a) (* (:im a) ratio)) denom)
                   (/ (- (:im a) (* (:re a) ratio)) denom)
                   {:flags flags :valid? valid? :op :div})))

      :else
      (let [ratio (if (zero? (:im b)) 0.0 (/ (:re b) (:im b)))
            denom (+ (:im b) (* (:re b) ratio))]
        (if (zero? denom)
          (complex 0.0 0.0 {:flags (conj flags :division-by-zero)
                             :valid? false
                             :op :div})
          (complex (/ (+ (* (:re a) ratio) (:im a)) denom)
                   (/ (- (* (:im a) ratio) (:re a)) denom)
                   {:flags flags :valid? valid? :op :div})))))
  )

(defn conj*
  [z]
  (complex (:re z) (- (:im z))
           {:flags (:flags z)
            :valid? (:valid? z)
            :op :conj}))

(defn csqrt
  "Principal square root of complex number."
  [z]
  (let [a (:re z)
        b (:im z)
        flags (:flags z)
        valid? (:valid? z)]
    (cond
      (and (zero? b) (>= a 0.0))
      (complex (math/sqrt a) 0.0 {:flags flags :valid? valid? :op :sqrt})

      (and (zero? b) (< a 0.0))
      (complex 0.0 (math/sqrt (- a)) {:flags flags :valid? valid? :op :sqrt})

      :else
      (let [r (magnitude z)
            t (math/sqrt (* 0.5 (+ r (Math/abs a))))
            real (if (>= a 0.0)
                   t
                   (/ (Math/abs b) (* 2.0 t)))
            imag (if (>= a 0.0)
                   (/ b (* 2.0 t))
                   (Math/copySign t b))]
        (complex real imag {:flags flags :valid? valid? :op :sqrt})))))

(def j
  "Imaginary unit."
  (complex 0.0 1.0))
