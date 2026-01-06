# Use Cases

These examples show what you can do with the library using analytical, surrogate, and reduced-order models—not full mesh-based solvers. Scan the headings to find relevant capabilities.

## 1) Propagate RF Through Uncertain Media (EM)
Compute attenuation and phase shift with uncertainty over materials and field distributions:
```clojure
(require '[physics.electromagnetics.fields :as f]
         '[physics.electromagnetics.materials :as m]
         '[physics.electromagnetics.propagation :as p])

(def field (f/->field {:type :electric
                       :frequency-hz 9.5e8
                       :amplitude {:type :gaussian :mean 5.0 :sd 0.1}
                       :orientation [0 0 1]}))
(def material (m/->material {:name "soil"
                             :type :dielectric
                             :permittivity-rel {:type :gaussian :mean 4.0 :sd 0.2}
                             :conductivity-s-m {:type :uniform :min 0.01 :max 0.05}}))

(p/propagate-monte-carlo field material 50.0 {:samples 200})
;; => stats for amplitude, attenuation, phase, valid sample count
```

## 2) Enforce EM Safety Constraints
Check power density and amplitude limits for safety or hardware envelopes:
```clojure
(require '[physics.electromagnetics.constraints :as ec]
         '[physics.electromagnetics.fields :as f]
         '[physics.electromagnetics.materials :as m])

(def field (f/->field {:type :electric :frequency-hz 2.4e9 :amplitude 120.0}))
(ec/evaluate-power-density field m/vacuum {:max-w-per-m2 5.0})
(ec/evaluate-field-amplitude field {:limit 60.0})
;; => violations with details
```

## 3) Geodetic ↔ ECEF ↔ ENU Transforms
Move between WGS84 geodetic, ECEF, and local ENU frames:
```clojure
(require '[physics.frames :as frames])

(def origin {:position [37.62 -122.38 0.0]})
(def target {:lat-deg 37.63 :lon-deg -122.40 :alt-m 50.0})
(def ecef (frames/geodetic->ecef target))
(def enu (frames/geodetic->enu origin target))
;; enu now gives local offsets relative to origin
```

## 4) Atmosphere & Ocean Profiles
Grab ISA atmosphere or seawater properties for dynamics/drag models:
```clojure
(require '[physics.environment :as env])

(env/isa-profile 10000.0)  ;; temp, pressure, density, viscosity, gravity at 10 km
(env/ocean-profile {:depth 200.0 :lat 60.0})  ;; seawater density/pressure at depth/lat
```

## 5) Airframe Dynamics + Envelope Check
Compute rigid-body derivatives for a fixed-wing model and validate constraints:
```clojure
(require '[physics.dynamics :as dyn]
         '[physics.models.common :as models]
         '[physics.environment :as env]
         '[physics.constraints :as constraints])

(def model models/fixed-wing)
(def state {:position [0 0 1000]
            :velocity [60 0 0]
            :orientation (physics.core/euler->quaternion {:roll 0 :pitch 0 :yaw 0})
            :angular-rate [0 0 0]})
(def env10k (env/isa-profile 1000))
(dyn/rigid-body-derivatives model state env10k {:throttle 0.5})
(constraints/evaluate-envelope model {:mach 0.2 :load-factor 2.0 :bank 20.0 :aoa 0.05})
```

## 6) Ground / Maritime / Subsurface Forces
Apply ground tire limits or maritime/subsurface drag/lift models:
```clojure
(require '[physics.dynamics :as dyn]
         '[physics.models.common :as models]
         '[physics.environment :as env])

(dyn/ground-forces models/ground-ugv {:terrain {:mu 0.7 :grade 0.1} :slip-angle-rad 0.2})
(dyn/maritime-forces models/maritime-usv {:velocity [5 0 0]} (env/ocean-profile {:depth 0}) {:rudder 0.1 :throttle 0.8})
(dyn/subsurface-forces models/submarine {:velocity [3 0 0]} (env/ocean-profile {:depth 100}) {:rudder 0.1 :planes 0.05 :throttle 0.6})
```

## 7) Orbital Derivatives
Compute orbital acceleration with J2 perturbation:
```clojure
(require '[physics.dynamics :as dyn]
         '[physics.models.common :as models])

(dyn/orbital-derivatives models/earth-body {:position [7000e3 0 0]
                                            :velocity [0 7.5e3 0]
                                            :perturbations? true})
```

## 8) Integrate Trajectories (RK4 / RKF45)
Integrate a simple 1D dynamical system or plug your derivative:
```clojure
(require '[physics.integrators :as integ])

(integ/rk4 {:derivative (fn [_ [x v]] [v -9.81])
            :initial-state [0.0 10.0]
            :t0 0.0 :dt 0.1 :steps 100 :store? true})
```

## 9) Project and Synthesise Telemetry
Forward-project a vehicle state under fixed controls and generate synthetic telemetry:
```clojure
(require '[physics.observer :as obs]
         '[physics.models.common :as models]
         '[physics.core :as core])

(def model models/fixed-wing)
(def state {:position [0 0 1000]
            :velocity [60 0 0]
            :orientation (core/euler->quaternion {:roll 0 :pitch 0 :yaw 0})
            :angular-rate [0 0 0]})
(obs/project model state {:throttle 0.6} {:horizon 10.0 :dt 0.1})
(obs/synthesise-telemetry model state {:rate 5 :duration 5.0 :controls {:throttle 0.6}})
```

## 10) Extended Kalman Filter (EKF) State Estimation
Estimate true state from noisy GPS/IMU measurements using a 6-DOF physics prior:
```clojure
(require '[physics.observer.ekf :as ekf]
         '[physics.math.linear :as lin]
         '[physics.models.common :as models])

(def model models/fixed-wing)
(def initial-guess {:position [0 0 1000] :velocity [60 0 0] :orientation [1 0 0 0] :angular-rate [0 0 0]})
(def P (lin/identity-mat 13)) ;; Initial covariance
(def Q (lin/scalar-mul (lin/identity-mat 13) 0.01)) ;; Process noise
(def R (lin/identity-mat 3)) ;; Measurement noise (GPS X/Y/Z)

;; 1. Predict (Forward propagate state and covariance)
(def pred (ekf/predict {:state-est initial-guess
                        :covariance P
                        :model model
                        :controls {:throttle 0.5}
                        :dt 0.1 :Q Q}))

;; 2. Update (Correct using GPS measurement Z at t=0.1)
(def H (lin/zeros 3 13)) ;; Matrix mapping state (13) to measurement (3)
;; Set H[0,0]=1, H[1,1]=1, H[2,2]=1 to observe Position X,Y,Z
(def measurement [10.0 5.0 1002.0])
(ekf/update-step pred H measurement R)
;; => {:state-est ... :covariance ... :innovation ...}
```

## 11) CFD Surrogate + Corrector
Validate geometry/environment, run a surrogate, and apply a corrector for plume or maritime flows:
```clojure
(require '[physics.cfd.plume :as plume])

(plume/predict {:geometry (plume/default-geometry)
                :environment (plume/default-environment)
                :parameters {:source-strength 10.0}})
;; => flow field with corrected values and metadata
```

## 12) Debris / Maritime / Shallow-Water Helpers
High-level wrappers for shallow-water grids and debris dispersion:
```clojure
(require '[physics.cfd.maritime :as maritime]
         '[physics.cfd.debris :as debris])

(maritime/predict {:parameters {:wave-height 1.5 :current [0.5 0 0]}})
(debris/predict {:parameters {:release-rate 2.0 :buoyancy 0.8}})
```

## 13) Surrogate Model Registry
Register and use custom surrogate models with validation:
```clojure
(require '[physics.cfd.surrogate :as s])

(s/register-model! {:id :custom-plume :path "models/plume.onnx"
                    :metadata {:author "you" :version "0.1"}})
(s/list-models)
(s/predict {:solver :plume
            :geometry (plume/default-geometry)
            :environment (plume/default-environment)
            :parameters {:emission-rate 5.0}})
```

## 14) EM Superposition & Power Density
Phasor superposition with safety checks:
```clojure
(require '[physics.electromagnetics.fields :as f]
         '[physics.electromagnetics.materials :as m])

(def f1 (f/->field {:type :electric :frequency-hz 5e9 :amplitude 10.0 :orientation [1 0 0] :phase-deg 0}))
(def f2 (f/->field {:type :electric :frequency-hz 5e9 :amplitude 10.0 :orientation [1 0 0] :phase-deg 90}))
(def sum (f/superpose [f1 f2]))
(f/power-density sum m/vacuum)
```

## 15) Spatial Geometry/Topology Utilities
Basic pose normalization and topology helpers:
```clojure
(require '[physics.spatial.pose :as pose]
         '[physics.spatial.geometry :as geom])

(pose/->pose {:position [0 0 0] :orientation [1 0 0 0]})
;; geometry/topology helpers can be composed for mesh or path processing
```

## 16) Field Operations: Tactical Intercept & Safety
Run a "Survival Mode" tactical loop: predict target motion with uncertainty, check safety constraints, and generate guidance.

```clojure
(require '[physics.ops.kinematics :as k]
         '[physics.ops.intercept :as int]
         '[physics.ops.safety :as safe]
         '[physics.ops.uncertainty :as unc])

;; 1. Current World State (with Uncertainty)
(def interceptor {:position [0 0 0] :velocity [20 0 0] :uncertainty {:pos 1.0}})
(def target      {:position [100 100 0] :velocity [-10 0 0] :uncertainty {:pos 5.0 :vel 1.0}})

;; 2. Propagate Target (Risk-Aware)
;; Projects position AND grows error bubble (Worst-Case Bounding)
(def pred-target (k/propagate target 5.0)) 

;; 3. Check Safety (Conservative)
;; Fails if error bubbles overlap or breach 50m separation
(def safety-check 
  (safe/conservative-predictive-safety? 
    interceptor target 
    (:uncertainty interceptor) (:uncertainty target)
    {:min-separation 50.0}
    {:horizon 10.0 :dt 1.0}))

;; 4. Generate Guidance
(if (:safe? safety-check)
  (int/guidance :lead (:position interceptor) 20.0 (:position target) (:velocity target))
  {:action :abort :reason (:violation safety-check)})
```

---

These examples are schematic by design—start here, then dive into the namespaces for details and additional options.

## Non-Goals
- Full-fidelity CFD/EM solvers; this library provides schemas, surrogates, and correctors.
- Domain-specific control or autonomy logic; this is a physics toolkit, not a planner/executor.
- UI/visualization; integrate with your preferred plotting/visualization stack.