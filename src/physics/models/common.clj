(ns physics.models.common
  "Curated physics models for representative platforms and celestial bodies.")

(def fixed-wing
  {:type :airframe
   :mass 12.5
   :wing-area 1.85
   :span 3.6
   :chord 0.58
   :inertia {:ixx 1.12 :iyy 1.35 :izz 2.05 :ixz 0.02}
   :aero {:cl0 0.28 :cla 5.7 :cl-de 0.75
          :cd0 0.032 :cd2 0.045
          :cy-beta -0.98 :cy-dr 0.17
          :cl-beta -0.12 :cl-da 0.08 :cl-dr 0.03 :cl-p -0.45 :cl-r 0.18
          :cm0 -0.02 :cma -1.12 :cm-de -1.25 :cm-q -12.0
          :cn-beta 0.25 :cn-da 0.02 :cn-dr -0.1 :cn-p -0.03 :cn-r -0.35}
   :propulsion {:max-thrust 150.0 :throttle-response 0.25}
   :limits {:alpha {:min -0.35 :max 0.6}
            :load-factor {:min -3.5 :max 7.5}
            :roll-rate 4.0
            :bank 80.0
            :mach 0.65}})

(def ground-ugv
  {:type :ground
   :mass 820.0
   :wheel-base 2.85
   :track-width 1.65
   :cornering {:front 62000.0 :rear 58000.0}
   :longitudinal {:max-force 9500.0}
   :limits {:slip-angle 0.6 :steering 0.7 :lateral-acc 9.0}
   :tires {:rolling-resistance 0.015 :mu 0.8}})

(def maritime-usv
  {:type :surface
   :mass 4200.0
   :displacement 4200.0
   :waterplane-area 12.0
  :wetted-area 52.0
   :hull {:drag-coefficient 0.0045 :lift-coefficient 0.6}
   :rudder {:area 0.6 :lever-arm 1.5 :lift-coefficient 5.2}
   :propulsor {:max-thrust 18000.0 :response 0.4}
   :limits {:heel 35.0 :draft 2.5 :speed 20.0}})

(def submarine
  {:type :subsurface
   :mass 185000.0
   :volume 180.0
   :length 70.0
   :beam 8.0
   :drag-coef 0.0055
   :rudder {:area 5.2 :lift-coefficient 6.0 :lever-arm 12.0}
   :planes {:area 7.5 :lift-coefficient 5.0 :lever-arm 15.0}
   :propulsor {:max-thrust 900000.0 :response 0.6}
   :limits {:max-depth 600.0 :pitch 30.0 :speed 18.0}})

(def earth-body
  {:type :celestial
   :radius 6378137.0
   :mu 3.986004418e14
   :j2 1.08262668e-3
   :rotation 7.2921159e-5})

(def models
  {:asset/fixed-wing fixed-wing
   :asset/ugv ground-ugv
   :asset/usv maritime-usv
   :asset/submarine submarine
   :body/earth earth-body})

(defn fetch
  [id]
  (if-let [m (get models id)]
    m
    (throw (ex-info "Unknown physics model identifier" {:id id}))))
