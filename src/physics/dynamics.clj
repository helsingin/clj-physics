(ns physics.dynamics
  "Dynamic models for aerial, ground, maritime, and orbital systems."
  (:require [clojure.math :as math]
            [physics.core :as core]
            [physics.environment :as env]
            [physics.models.common :as models]))

(defn fetch-model [id]
  (models/fetch id))

;; ---------- Airframe (6-DoF rigid body) ------------------------------------

(defn- orientation-quaternion [state]
  (cond
    (:orientation state) (:orientation state)
    (:attitude state) (core/euler->quaternion (:attitude state))
    :else [1.0 0.0 0.0 0.0]))

(defn- body-rotation-matrix [state]
  (core/quaternion->dcm (orientation-quaternion state)))

(defn- inertial->body-matrix [state]
  (core/transpose (body-rotation-matrix state)))

(defn- dynamic-pressure [rho speed]
  (* 0.5 rho (* speed speed)))

(defn- aerodynamic-coefficients [model {:keys [alpha beta controls rates]}]
  (let [{:keys [aero span-m chord-m]} model
        {:keys [roll pitch yaw]} rates
        {:keys [aileron elevator rudder]} controls
        {:keys [cl0 cla cl-de cd0 cd2 cy-beta cy-dr cl-beta cl-da cl-dr cl-p cl-r cm0 cma cm-de cm-q cn-beta cn-da cn-dr cn-p cn-r]} aero
        cl (+ cl0 (* cla alpha) (* cl-de elevator))
        cd (+ cd0 (* cd2 cl cl))
        cy (+ (* cy-beta beta) (* cy-dr rudder))
        pb (* 0.5 span-m roll)
        rb (* 0.5 span-m yaw)
        cl-moment (+ (* cl-beta beta) (* cl-da aileron) (* cl-dr rudder) (* cl-p pb) (* cl-r rb))
        cm (+ cm0 (* cma alpha) (* cm-de elevator) (* cm-q (* 0.5 chord-m pitch)))
        cn (+ (* cn-beta beta) (* cn-da aileron) (* cn-dr rudder) (* cn-p pb) (* cn-r rb))]
    {:cl cl :cd cd :cy cy :cl-roll cl-moment :cm cm :cn cn}))

(defn- gravity-body [model state g]
  (let [mass (:mass-kg model)
        weight [0.0 0.0 (* -1.0 mass g)]
        Rib (inertial->body-matrix state)]
    (core/matmul Rib weight)))

(defn- aerodynamic-forces [model state env controls]
  (let [mass (:mass-kg model)
        [vx vy vz] (:velocity state)
        Rib (inertial->body-matrix state)
        v-body (core/matmul Rib [vx vy vz])
        speed (core/magnitude v-body)
        epsilon 1e-6
        [u v w] v-body
        alpha (math/atan2 w (max epsilon u))
        beta (math/asin (core/clamp (/ v (max epsilon speed)) -0.99 0.99))
        rho (:density-kg-per-m3 env)
        q-bar (dynamic-pressure rho speed)
        controls (merge {:aileron 0.0 :elevator 0.0 :rudder 0.0 :throttle 0.0}
                        controls)
        span (:span-m model)
        chord (:chord-m model)
        [p q r] (or (:angular-rate state) [0.0 0.0 0.0])
        rates {:roll (if (pos? speed) (/ (* p span) (* 2.0 speed)) 0.0)
               :pitch (if (pos? speed) (/ (* q chord) (* 2.0 speed)) 0.0)
               :yaw (if (pos? speed) (/ (* r span) (* 2.0 speed)) 0.0)}
        coeffs (aerodynamic-coefficients model {:alpha alpha
                                                :beta beta
                                                :controls controls
                                                :rates rates})
        S (:wing-area-m2 model)
        b span
        c chord
        lift (* (:cl coeffs) q-bar S)
        drag (* (:cd coeffs) q-bar S)
        side (* (:cy coeffs) q-bar S)
        sin-a (math/sin alpha)
        cos-a (math/cos alpha)
        fx (+ (- (* drag cos-a)) (* lift sin-a))
        fz (+ (- (* drag sin-a)) (- (* lift cos-a)))
        fy side
        l (* (:cl-roll coeffs) q-bar S b)
        m (* (:cm coeffs) q-bar S c)
        n (* (:cn coeffs) q-bar S b)]
    {:force [fx fy fz]
     :moment [l m n]
     :dynamic-pressure q-bar
     :alpha alpha
     :beta beta
     :speed speed
     :weight mass}))

(defn airframe-forces
  "Compute aerodynamic, thrust, and weight forces for a fixed-wing platform."
  [model state env controls]
  (let [rho-env (or env (env/isa-profile (max 0.0 (nth (:position state) 2))))
        aero (aerodynamic-forces model state rho-env controls)
        g (:gravity-m-s2 rho-env)
        weight (gravity-body model state g)
        throttle (core/clamp (:throttle controls 0.0) 0.0 1.0)
        thrust-mag (* throttle (get-in model [:propulsion :max-thrust-n]))
        thrust [thrust-mag 0.0 0.0]
        net-force (mapv + (:force aero) weight thrust)
        net-moment (:moment aero)]
    {:net-force net-force
     :net-torque net-moment
     :aero-force (:force aero)
     :weight weight
     :thrust thrust
     :angles {:alpha (:alpha aero) :beta (:beta aero)}
     :dynamic-pressure (:dynamic-pressure aero)
     :speed (:speed aero)}))

(defn rigid-body-derivatives
  "Return time derivatives for a 6-DoF rigid body given state map and controls."
  [model state env controls]
  (let [forces (airframe-forces model state env controls)
        mass (:mass-kg model)
        inertia (:inertia model)
        [p q r] (or (:angular-rate state) [0.0 0.0 0.0])
        [L M N] (:net-torque forces)
        {:keys [ixx-kgm2 iyy-kgm2 izz-kgm2 ixz-kgm2]} inertia
        ixz (or ixz-kgm2 0.0)
        a ixx-kgm2
        b iyy-kgm2
        c (- ixz)
        e izz-kgm2
        denom (- (* a e) (* c c))
        inertia-matrix [[ixx-kgm2 0.0 (- ixz)]
                        [0.0 iyy-kgm2 0.0]
                        [(- ixz) 0.0 izz-kgm2]]
        inv-mat [[(/ e denom) 0.0 (/ (- c) denom)]
                 [0.0 (/ 1.0 b) 0.0]
                 [(/ (- c) denom) 0.0 (/ a denom)]]
        Iw (core/matmul inertia-matrix [p q r])
        coupling (core/cross [p q r] Iw)
        rhs (mapv - [L M N] coupling)
        ang-acc (core/matmul inv-mat rhs)
        Rib (body-rotation-matrix state)
        accel-inertial (core/matmul Rib (mapv #(/ % mass) (:net-force forces)))
        qdot (core/quaternion-derivative (orientation-quaternion state) [p q r])
        state-vector {:position (:velocity state)
                      :velocity accel-inertial
                      :orientation qdot
                      :angular-rate ang-acc}]
    (assoc state-vector :aux forces)))

;; ---------- Ground vehicle --------------------------------------------------

(defn ground-forces
  [model state]
  (let [mass (:mass-kg model)
        mu (or (get-in state [:terrain :mu]) (get-in model [:tires :mu]))
        grade (or (get-in state [:terrain :grade-rad]) 0.0)
        g env/g0
        normal (* mass g (math/cos grade))
        max-lateral (* mu normal)
        Cf (or (get-in model [:cornering-n-per-rad :front]) 0.0)
        Cr (or (get-in model [:cornering-n-per-rad :rear]) 0.0)
        slip-limit (or (get-in model [:limits :slip-angle-rad]) 0.6)
        slip (core/clamp (:slip-angle-rad state 0.0) (- slip-limit) slip-limit)
        Fy (core/clamp (- (+ (* Cf slip) (* Cr slip))) (- max-lateral) max-lateral)]
    {:lateral-force Fy
     :max-lateral-force max-lateral
     :normal-force normal}))

;; ---------- Maritime --------------------------------------------------------

(defn maritime-forces
  [model state env controls]
  (let [{:keys [density-kg-per-m3]} env
        rho (or density-kg-per-m3 1025.0)
        speed (core/magnitude (:velocity state))
        Cf (get-in model [:hull :drag-coefficient])
        wetted (:wetted-area-m2 model)
        drag (* 0.5 rho Cf wetted speed speed)
        rudder (:rudder model)
        rudder-def (core/clamp (:rudder controls 0.0) -0.4 0.4)
        lift (* 0.5 rho (:lift-coefficient rudder) (:area-m2 rudder) speed speed rudder-def)
        lever (:lever-arm-m rudder)
        throttle (core/clamp (:throttle controls 0.0) 0.0 1.0)
        thrust (* throttle (get-in model [:propulsor :max-thrust-n]))]
    {:drag drag
     :lift lift
     :yaw-moment (* lift lever)
     :thrust thrust}))

(defn subsurface-forces
  [model state env controls]
  (let [{:keys [density-kg-per-m3]} env
        rho (or density-kg-per-m3 1025.0)
        speed (core/magnitude (:velocity state))
        drag (* 0.5 rho (:drag-coef model) (* (:length-m model) (:beam-m model)) speed speed)
        planes (:planes model)
        plane-def (core/clamp (:planes controls 0.0) -0.3 0.3)
        lift (* 0.5 rho (:lift-coefficient planes) (:area-m2 planes) speed speed plane-def)
        lever (:lever-arm-m planes)
        rudder (:rudder model)
        rudder-def (core/clamp (:rudder controls 0.0) -0.35 0.35)
        yaw (* 0.5 rho (:lift-coefficient rudder) (:area-m2 rudder) speed speed rudder-def)
        throttle (core/clamp (:throttle controls 0.0) 0.0 1.0)
        thrust (* throttle (get-in model [:propulsor :max-thrust-n]))]
    {:drag drag
     :lift lift
     :pitch-moment (* lift lever)
     :yaw-moment (* yaw (:lever-arm-m rudder))
     :thrust thrust}))

;; ---------- Orbital dynamics -----------------------------------------------

(defn orbital-derivatives
  [body {:keys [position velocity perturbations?] :as state}]
  (let [[x y z] position
        [vx vy vz] velocity
        r (core/magnitude position)
        mu (:mu-m3-s2 body)
        j2 (:j2 body)
        re (:radius-m body)
        grav-factor (/ mu (math/pow r 3))
        base (mapv #(* -1.0 grav-factor %) position)
        if-j2 (when (and perturbations? j2)
                (let [z2 (* z z)
                      r2 (* r r)
                      k (* 1.5 j2 mu (math/pow re 2) (/ 1.0 (math/pow r 5)))
                      c (- 5.0 (/ z2 r2))]
                  [(* k x c)
                   (* k y c)
                   (* k z (- (+ c 1.0)))]))
        accel (mapv + base (or if-j2 [0.0 0.0 0.0]))]
    {:acceleration accel
     :velocity [(math/sqrt (/ mu r)) 0.0 0.0]
     :actual-velocity velocity
     :position position}))
