(ns physics.observer
  "Trajectory projection and telemetry synthesis built on physics dynamics."
  (:require [clojure.math :as math]
            [physics.core :as core]
            [physics.dynamics :as dyn]
            [physics.environment :as env]
            [physics.integrators :as integ]))

(def ^:private state-order
  [:px :py :pz :vx :vy :vz :qw :qx :qy :qz :p :q :r])

(defn- orientation-quaternion [state]
  (cond
    (:orientation state) (:orientation state)
    (:attitude state) (core/euler->quaternion (:attitude state))
    :else [1.0 0.0 0.0 0.0]))

(defn- state->vector [state]
  (let [[px py pz] (:position state)
        [vx vy vz] (:velocity state)
        q (orientation-quaternion state)
        [qw qx qy qz] q
        [p q r] (or (:angular-rate state) [0.0 0.0 0.0])]
    [px py pz vx vy vz qw qx qy qz p q r]))

(defn- vector->state [v]
  (let [[px py pz vx vy vz qw qx qy qz p q r] v]
    {:position [px py pz]
     :velocity [vx vy vz]
     :orientation (core/quaternion-normalize [qw qx qy qz])
     :angular-rate [p q r]}))

(defn- derivative->vector [derivative]
  (let [[dx dy dz] (:position derivative)
        [dvx dvy dvz] (:velocity derivative)
        [dqw dqx dqy dqz] (:orientation derivative)
        [dp dq dr] (:angular-rate derivative)]
    [dx dy dz dvx dvy dvz dqw dqx dqy dqz dp dq dr]))

(defn- altitude-from-state [state]
  (let [[_ _ z] (:position state)]
    (max 0.0 z)))

(defn- default-env-provider [state _]
  (env/isa-profile (altitude-from-state state)))

(defn- derivative-fn [model controls env-provider]
  (fn [t state-vec]
    (let [state (vector->state state-vec)
          env (env-provider state t)
          deriv (dyn/rigid-body-derivatives model state env controls)]
      (derivative->vector deriv))))

(defn project
  "Forward project rigid-body state under fixed controls.

  Options:
    :horizon  integration horizon seconds (required)
    :dt       step size seconds (defaults to 0.1)
    :environment fn(state t) -> env map (defaults to ISA atmosphere)
    :store?   include intermediate samples (default true)

  Returns {:trajectory [...states...] :time horizon :step dt}."
  [model state controls {:keys [horizon dt environment store?]
                         :or {dt 0.1 store? true}}]
  (when-not horizon
    (throw (ex-info "Projection requires :horizon" {})))
  (let [controls (merge {:aileron 0.0 :elevator 0.0 :rudder 0.0 :throttle 0.0}
                        controls)
        model-type (:type model)
        steps (max 1 (int (math/ceil (/ horizon dt))))]
    (case model-type
      :ground
      (let [trajectory (mapv (fn [idx]
                               (let [time (* idx dt)
                                     displacement (mapv #(* time %) (:velocity state))]
                                 (-> state
                                     (assoc :timestamp time)
                                     (assoc :position (mapv + (:position state) displacement)))))
                             (range (inc steps)))]
        {:trajectory trajectory
         :time (* dt steps)
         :step dt})

      :airframe
      (let [env-provider (or environment default-env-provider)
            initial (state->vector state)
            result (integ/rk4 {:derivative (derivative-fn model controls env-provider)
                               :initial-state initial
                               :t0 0.0
                               :dt dt
                               :steps steps
                               :store? true})
            history (:history result)
            sample-step (max 1 (int (Math/round (/ 1.0 dt))))
            sampled-indices (distinct (concat (range 0 (count history) sample-step)
                                              [(dec (count history))]))
            trajectory (mapv (fn [idx]
                               (let [{:keys [t state]} (nth history idx)]
                                 (assoc (vector->state state) :timestamp t)))
                             sampled-indices)]
        {:trajectory trajectory
         :time (* dt steps)
         :step dt})

      ;; default fallback: simple kinematics
      (let [trajectory (mapv (fn [idx]
                               (let [time (* idx dt)
                                     displacement (mapv #(* time %) (:velocity state))]
                                 (-> state
                                     (assoc :timestamp time)
                                     (assoc :position (mapv + (:position state) displacement))
                                     (assoc :velocity (:velocity state)))))
                             (range (inc steps)))]
        {:trajectory trajectory
         :time (* dt steps)
         :step dt}))))

(defn synthesise-telemetry
  "Generate synthetic telemetry frames at fixed sample rate.

  Options:
    :rate samples per second (required)
    :duration seconds (required)
    :environment same as project
    :controls control schedule (defaults to constant controls)

  Returns vector of telemetry maps with :timestamp, :position, :velocity, :orientation, :angular-rate."
  [model state {:keys [rate duration controls environment]
                :as opts}]
  (let [rate (or rate (throw (ex-info "Telemetry requires :rate" {})))
        duration (or duration (throw (ex-info "Telemetry requires :duration" {})))
        dt (/ 1.0 rate)
        projection (project model state controls {:horizon duration
                                                  :dt dt
                                                  :environment environment
                                                  :store? true})]
    (map-indexed (fn [idx sample]
                   {:timestamp (* idx dt)
                    :position (:position sample)
                    :velocity (:velocity sample)
                    :orientation (:orientation sample)
                    :angular-rate (:angular-rate sample)})
                 (:trajectory projection))))
