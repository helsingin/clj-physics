(ns physics.models.common
  "Curated physics models for representative platforms and celestial bodies.")

(def fixed-wing
  {:type :airframe
   :mass-kg 12.5
   :wing-area-m2 1.85
   :span-m 3.6
   :chord-m 0.58
   :inertia {:ixx-kgm2 1.12 :iyy-kgm2 1.35 :izz-kgm2 2.05 :ixz-kgm2 0.02}
   :aero {:cl0 0.28 :cla 5.7 :cl-de 0.75
          :cd0 0.032 :cd2 0.045
          :cy-beta -0.98 :cy-dr 0.17
          :cl-beta -0.12 :cl-da 0.08 :cl-dr 0.03 :cl-p -0.45 :cl-r 0.18
          :cm0 -0.02 :cma -1.12 :cm-de -1.25 :cm-q -12.0
          :cn-beta 0.25 :cn-da 0.02 :cn-dr -0.1 :cn-p -0.03 :cn-r -0.35}
   :propulsion {:max-thrust-n 150.0 :throttle-response 0.25}
   :limits {:alpha-rad {:min -0.35 :max 0.6}
            :load-factor-g {:min -3.5 :max 7.5}
            :roll-rate-rad-s 4.0
            :bank-deg 80.0
            :mach-limit 0.65}})

(def ground-ugv
  {:type :ground
   :mass-kg 820.0
   :wheel-base-m 2.85
   :track-width-m 1.65
   :cornering-n-per-rad {:front 62000.0 :rear 58000.0}
   :longitudinal {:max-force-n 9500.0}
   :limits {:slip-angle-rad 0.6 :steering-rad 0.7 :lateral-acc-m-s2 9.0}
   :tires {:rolling-resistance 0.015 :mu 0.8}})

(def maritime-usv
  {:type :surface
   :mass-kg 4200.0
   :displacement-kg 4200.0
   :waterplane-area-m2 12.0
  :wetted-area-m2 52.0
   :hull {:drag-coefficient 0.0045 :lift-coefficient 0.6}
   :rudder {:area-m2 0.6 :lever-arm-m 1.5 :lift-coefficient 5.2}
   :propulsor {:max-thrust-n 18000.0 :response 0.4}
   :limits {:heel-deg 35.0 :draft-m 2.5 :speed-m-s 20.0}})

(def submarine
  {:type :subsurface
   :mass-kg 185000.0
   :volume-m3 180.0
   :length-m 70.0
   :beam-m 8.0
   :drag-coef 0.0055
   :rudder {:area-m2 5.2 :lift-coefficient 6.0 :lever-arm-m 12.0}
   :planes {:area-m2 7.5 :lift-coefficient 5.0 :lever-arm-m 15.0}
   :propulsor {:max-thrust-n 900000.0 :response 0.6}
   :limits {:max-depth-m 600.0 :pitch-deg 30.0 :speed-m-s 18.0}})

(def earth-body
  {:type :celestial
   :radius-m 6378137.0
   :mu-m3-s2 3.986004418e14
   :j2 1.08262668e-3
   :rotation-rad-s 7.2921159e-5})

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
