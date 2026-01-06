# clj-physics

**A Data-Oriented Toolkit for High-Assurance Simulation & Operational Modeling**

`clj-physics` is a robust Clojure library designed for simulating physical systems where speed, data transparency, and safety matter more than microscopic visual fidelity.

Unlike a game engine (which prioritizes frame rate) or a finite-element solver (which prioritizes microscopic accuracy at high computational cost), `clj-physics` is built for **Operational Modeling**: asking "what if?" questions in real-time, generating synthetic training data for AI, or running tactical loops in "messy" environments.

## What You Can Do

### 1. Simulate Vehicles (Dynamics & Environment)
Model the motion of aircraft, submarines, and ground vehicles with high fidelity 6-DOF dynamics.
*   **Physics:** Validated rigid-body models using the **1976 US Standard Atmosphere** and **UNESCO 1983 Ocean Equation of State**.
*   **Integrators:** Adaptive **Runge-Kutta-Fehlberg (RKF45)** solvers ensure accuracy without wasting cycles.
*   **Control:** Provide a state map (position, velocity, orientation) and controls (throttle, rudder), and the library calculates time derivatives handling gravity, drag, and propulsion.

### 2. Run Tactical "Field Operations"
A "Survival Mode" layer designed for mission planners and autonomy systems that must operate on noisy, imperfect data.
*   **Intercepts:** Calculate exactly when/where to aim to intercept a target, with stable math (Citardauq formula) and "Impossible" scenario detection.
*   **Safety Bubbles:** Check if a drone breaches a No-Fly Zone (3D Prism), accounting for **Worst-Case Error Bounding** ($E \sim t^2$).
*   **Risk-Awareness:** Calculates uncertainty bounds, telling you not just where a target *is*, but the entire probability envelope of where it *could be*.

### 3. Model Invisible Phenomena (Surrogates)
Use **Surrogate Models** to get 90% accurate answers for fluid and EM problems in milliseconds.
*   **CFD (Fluid Dynamics):** Instantly generate flow fields (wind, chemical plumes) using a "Surrogate + Corrector" pipeline (Helmholtz-Hodge decomposition) to ensure physical plausibility (mass conservation).
*   **Electromagnetics:** Simulate RF signal attenuation through rain, soil, or walls using **Monte Carlo** propagation to account for material uncertainty. Supports full phasor arithmetic and power density safety checks.

### 4. Estimate State (Kalman Filters)
Recover "True" state from noisy sensors (like jittery GPS).
*   **EKF:** Built-in **Extended Kalman Filter** with automatic numerical Jacobian differentiation.
*   **Fusion:** Fuse physics-based predictions with noisy measurements to track position, velocity, and orientation covariance.

## Why use this over a game engine?

1.  **Data-Oriented:** Every entity—a plane, a radio wave, a wind field—is just a Clojure map. There are no opaque objects. You can save, inspect, or send simulation state over a wire instantly.
2.  **High-Assurance Safety:** The library enforces SI units (keys like `:mass-kg`, `:pressure-pa`) and includes "Survival Mode" guardrails. It handles `NaN` inputs gracefully by degrading performance rather than crashing the JVM.
3.  **Determinism:** The math is rigorous to ensure that a simulation running on a laptop yields the exact same result as one running on a server, critical for distributed training and testing.

## Install

Pull the latest version from [Clojars](https://clojars.org/net.clojars.helsingin/physics):

```clojure
;; deps.edn
net.clojars.helsingin/physics {:mvn/version "RELEASE"}
```

## Quick Start

### 1. Dynamics: Fly a Plane
```clojure
(require '[physics.dynamics :as dyn]
         '[physics.models.common :as models]
         '[physics.environment :as env]
         '[physics.core :as core])

(def model models/fixed-wing)
(def state {:position [0 0 1000]
            :velocity [60 0 0]
            :orientation (core/euler->quaternion {:roll 0 :pitch 0 :yaw 0})
            :angular-rate [0 0 0]})

;; Compute forces at 1,000m altitude
(dyn/rigid-body-derivatives model state (env/isa-profile 1000) {:throttle 0.5})
```

### 2. Field Ops: Tactical Intercept
*"Can I reach the target?"*
```clojure
(require '[physics.ops.intercept :as int])

;; Calculate lead pursuit vector for a target moving at 10m/s
(int/guidance :lead 
              [0 0 0]   ;; My Position
              100.0     ;; My Speed
              [500 0 0] ;; Target Position
              [0 10 0]) ;; Target Velocity
;; => {:aim-vector [0.196 0.98 ...], :status :valid}
```

### 3. Electromagnetics: RF Safety
```clojure
(require '[physics.electromagnetics.fields :as f]
         '[physics.electromagnetics.constraints :as c])

(def radar-pulse (f/->field {:type :electric :frequency-hz 9e9 :amplitude 200.0}))
(c/evaluate-field-amplitude radar-pulse {:limit 100.0})
;; => Checks if amplitude exceeds hardware limits
```

## Tests

```bash
clojure -M:test
```

## Build and Release
- Build: `make jar`
- Deploy: `make deploy`

## License
Licensed under GPL-3.0-only. See `LICENSE` for details.
