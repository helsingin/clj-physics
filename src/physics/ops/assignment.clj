(ns physics.ops.assignment
  "Tactical assignment logic: scoring, spatial indexing, and feasibility filtering.
   Survival Mode: Costs degrade to Infinity on error; Filters safe-fail to empty lists."
  (:require [physics.core :as core]
            [physics.ops.kinematics :as k]
            [physics.ops.intercept :as int]
            [malli.core :as m]))

;; --- Cost Evaluation ---

(defn- calculate-cost [candidate weights]
  (try
    (reduce-kv (fn [sum k weight]
                 (let [val (get candidate k)]
                   ;; CRITICAL FIX: Missing data is NOT free. It is a showstopper.
                   (if (and (number? val) (Double/isFinite val))
                     (+ sum (* (double val) (double weight)))
                     (reduced Double/POSITIVE_INFINITY))))
               0.0
               weights)
    (catch Exception _ Double/POSITIVE_INFINITY)))

(defn cost-matrix
  "Calculate weighted cost for each candidate.
   Returns { :id -> cost }.
   If any value is NaN/Inf OR MISSING, the total cost for that candidate becomes Infinite."
  [candidates weights]
  (into {}
        (for [c candidates]
          [(:id c) (calculate-cost c weights)])))

;; --- Spatial Logic ---

(defn- get-pos [entity]
  (cond
    (vector? entity) entity
    (map? entity)    (:position entity)
    :else            nil))

(defn- v-dist-sq [a b]
  (let [dx (- (nth a 0 0.0) (nth b 0 0.0))
        dy (- (nth a 1 0.0) (nth b 1 0.0))
        dz (- (nth a 2 0.0) (nth b 2 0.0))]
    (+ (* dx dx) (* dy dy) (* dz dz))))

(defn nearest-neighbor
  "Find the candidate closest to the target point (using squared distance).
   Handles both raw vectors and {:position ...} maps.
   Ignores candidates with invalid/missing positions."
  [target candidates]
  (let [valid-candidates (filter #(k/finite? (get-pos %)) candidates)]
    (when (seq valid-candidates)
      (apply min-key 
             #(v-dist-sq target (get-pos %)) 
             valid-candidates))))

(defn- azimuth-deg [center target]
  (let [t-pos (get-pos target)
        c-pos (get-pos center)]
    (if (and (k/finite? t-pos) (k/finite? c-pos))
      (let [dx (- (nth t-pos 0) (nth c-pos 0))
            dy (- (nth t-pos 1) (nth c-pos 1))
            ;; Nav standard: 0 is North (+Y), 90 is East (+X)
            az (Math/toDegrees (Math/atan2 dx dy))]
        (mod az 360.0))
      nil)))

(defn- in-sector? [az {:keys [min-az max-az]}]
  (when az
    (if (> min-az max-az)
      (or (>= az min-az) (<= az max-az)) ;; Crossing North
      (and (>= az min-az) (<= az max-az)))))

(defn assign-sectors
  "Map targets/entities to sectors based on azimuth relative to center.
   Returns { :sector-id -> #{targets...} }.
   Gracefully ignores entities with invalid positions."
  [targets center sectors]
  (reduce (fn [acc target]
            (let [az (azimuth-deg center target)
                  match (some #(when (in-sector? az %) (:id %)) sectors)]
              (if match
                (update acc match (fnil conj #{}) target)
                acc)))
          {}
          targets))

;; --- Feasibility ---

(defn filter-feasible
  "Filter candidates based on a predicate function.
   Safely handles exceptions in the predicate (treating them as infeasible)."
  [candidates constraint-fn]
  (filter (fn [c]
            (try
              (constraint-fn c)
              (catch Exception _ false)))
          candidates))