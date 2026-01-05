(ns physics.observer.ekf
  "Extended Kalman Filter for 6-DoF rigid body state estimation."
  (:require [physics.core :as core]
            [physics.dynamics :as dyn]
            [physics.integrators :as integ]
            [physics.observer :as obs]
            [physics.math.linear :as lin]
            [physics.environment :as env]))

(def ^:private epsilon 1e-4)

(defn- numerical-jacobian
  "Compute the Jacobian F = df/dx using central differences.
   f: (t, x) -> x_dot (state derivative vector)
   x: state vector (13 elements)"
  [f t x]
  (let [n (count x)]
    (vec
     (for [i (range n)]
       (vec
        (for [j (range n)]
          (let [delta (assoc (vec (repeat n 0.0)) j epsilon)
                x-plus (mapv + x delta)
                x-minus (mapv - x delta)
                f-plus (f t x-plus)
                f-minus (f t x-minus)]
            (/ (- (nth f-plus i) (nth f-minus i))
               (* 2.0 epsilon)))))))))

(defn- derivative-wrapper [model controls env-provider]
  (fn [t state-vec]
    (let [state (obs/vector->state state-vec)
          env (env-provider state t)
          deriv (dyn/rigid-body-derivatives model state env controls)]
      ;; Map deriv map to vector
      [;; dPos/dt = Velocity
       (nth (:position deriv) 0) (nth (:position deriv) 1) (nth (:position deriv) 2)
       ;; dVel/dt = Accel
       (nth (:velocity deriv) 0) (nth (:velocity deriv) 1) (nth (:velocity deriv) 2)
       ;; dQuat/dt = Qdot
       (nth (:orientation deriv) 0) (nth (:orientation deriv) 1) (nth (:orientation deriv) 2) (nth (:orientation deriv) 3)
       ;; dRate/dt = Angular Accel
       (nth (:angular-rate deriv) 0) (nth (:angular-rate deriv) 1) (nth (:angular-rate deriv) 2)])))

(defn predict
  "EKF Prediction Step.
   Returns {:state-est ... :covariance ...}"
  [{:keys [state-est covariance model controls environment dt Q]}]
  (let [env-provider (or environment (fn [s _] (env/isa-profile (max 0.0 (nth (:position s) 2)))))
        f (derivative-wrapper model controls env-provider)
        x-k (obs/state->vector state-est)
        ;; Propagate state using RK4
        rk-result (integ/rk4 {:derivative f
                              :initial-state x-k
                              :dt dt
                              :steps 1})
        x-pred (:state rk-result)
        ;; Compute Jacobian F at current estimate
        F (numerical-jacobian f 0.0 x-k)
        ;; Approximate discrete transition matrix Phi = I + F*dt
        n (count x-k)
        I (lin/identity-mat n)
        Phi (lin/mat-add I (lin/scalar-mul F dt))
        ;; P_pred = Phi * P_k * Phi^T + Q
        P-pred (lin/mat-add (lin/mat-mul (lin/mat-mul Phi covariance) (lin/transpose Phi)) Q)]
    {:state-est (obs/vector->state x-pred)
     :covariance P-pred}))

(defn update-step
  "EKF Update Step.
   H: Measurement matrix (m x n)
   z: Measurement vector (m)
   R: Measurement noise covariance (m x m)"
  [{:keys [state-est covariance]} H z R]
  (let [x (obs/state->vector state-est)
        P covariance
        ;; y = z - Hx
        Hx (vec (map (fn [row] (core/dot row x)) H))
        y (mapv - z Hx)
        ;; S = H P H^T + R
        HT (lin/transpose H)
        PHt (lin/mat-mul P HT)
        S (lin/mat-add (lin/mat-mul H PHt) R)
        ;; K = P H^T S^-1
        S-inv (lin/invert S)
        K (lin/mat-mul PHt S-inv)
        ;; x_new = x + Ky
        Ky (vec (map (fn [row] (core/dot row y)) K))
        x-new (mapv + x Ky)
        ;; P_new = (I - KH) P
        n (count x)
        I (lin/identity-mat n)
        KH (lin/mat-mul K H)
        P-new (lin/mat-mul (lin/mat-sub I KH) P)]
    {:state-est (obs/vector->state x-new)
     :covariance P-new
     :innovation y}))
