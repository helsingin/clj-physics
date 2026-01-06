# clj-physics

**A Data-Oriented Toolkit for High-Assurance Simulation & Operational Modeling**

`clj-physics` is a high-assurance, data-oriented toolkit designed to simulate physical systems where speed, robustness, and data transparency matter more than microscopic visual fidelity.

Unlike a game engine (which prioritizes frame rate) or a finite-element solver (which prioritizes microscopic accuracy at high computational cost), `clj-physics` is built for **Operational Modeling**: asking "what if?" questions in real-time, generating synthetic training data for AI, or running tactical loops in "messy" environments.

## What You Can Do

### 1. Simulate Vehicles (6-DOF Dynamics)
Model the motion of aircraft, submarines, and ground vehicles with high fidelity.
*   **What you can do:** Simulate a fixed-wing aircraft performing a maneuver using the **1976 US Standard Atmosphere** and rigid-body dynamics.
*   **How it works:** You provide a state map (position, velocity, orientation) and controls (throttle, rudder). The library gives you the time derivatives, handling gravity, aerodynamics, and propulsion automatically.
*   **Safety:** It uses adaptive integrators (**Runge-Kutta-Fehlberg**) to ensure accuracy without wasting cycles.

### 2. Run Tactical "Field Operations"
This is the library's **"Survival Mode"**—a layer designed for mission planners and autonomy systems that must operate on noisy, imperfect data.
*   **Intercepts:** Calculate exactly when and where to aim to intercept a moving target, with mathematical proofs for "Impossible" scenarios.
*   **Safety Bubbles:** Check if a drone will breach a No-Fly Zone (3D Prism), accounting for the fact that its position error grows over time ($E \sim t^2$).
*   **Risk-Awareness:** Unlike standard physics engines, this layer calculates **Uncertainty Bounds**. It tells you not just where the target *is*, but the entire probability envelope of where it *could be*.

### 3. Model Invisible Phenomena (Surrogates)
Instead of taking hours to solve a fluid dynamics or electromagnetic problem, `clj-physics` uses **Surrogate Models** to give you 90% accurate answers in milliseconds.
*   **CFD (Fluid Dynamics):** Instantly generate a wind flow field around a building or model a chemical plume dispersion. It uses a "Surrogate + Corrector" pipeline to ensure the wind doesn't blow *through* walls.
*   **Electromagnetics:** Simulate how a radio signal weakens (attenuates) as it passes through rain, soil, or walls using **Monte Carlo** methods to account for material uncertainty.

### 4. Estimate State (Kalman Filters)
If you have noisy sensors (like a jittery GPS), you can use the built-in **Extended Kalman Filter (EKF)**.
*   **What you can do:** Fuse a physics prediction with noisy measurements to recover the "True" position and velocity of a platform.
*   **Feature:** It includes an automatic numerical Jacobian, so you don't have to do calculus by hand to derive the filter matrices.

---

## Why use this over a game engine?

1.  **Data-Oriented:** Every entity—a plane, a radio wave, a wind field—is just a Clojure map. There are no opaque objects. You can save, inspect, or send any simulation state over a wire instantly.
2.  **Units & Safety:** The library enforces SI units (keys like `:mass-kg`, `:pressure-pa`) and includes "Survival Mode" guardrails. It won't crash if you feed it `NaN`; it degrades gracefully.
3.  **Determinism:** The math is rigorous (e.g., using **Citardauq formulas** for quadratics) to ensure that a simulation running on a laptop yields the exact same result as one running on a server.

## Install

Pull the latest version from [Clojars](https://clojars.org/net.clojars.helsingin/physics):

```clojure
;; deps.edn
net.clojars.helsingin/physics {:mvn/version "RELEASE"}
```

## Example: A Tactical Query
*"Can my interceptor reach the target before it enters the restricted zone?"*

```clojure
(require '[physics.ops.kinematics :as k]
         '[physics.ops.intercept :as int]
         '[physics.ops.safety :as safe])

;; 1. Predict where the target is going (Risk-Aware)
(def future-target (k/propagate target-state 10.0))

;; 2. Check if that future position breaches the safety perimeter
(def safe? (safe/conservative-predictive-boundary? 
             future-target 
             (:uncertainty future-target)
             restricted-zone-polygon
             {:min-alt 0 :max-alt 500}))

;; 3. If safe, calculate the intercept course
(when (:safe? safe?)
  (int/time-to-go interceptor-pos (:position future-target) interceptor-speed))
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

### 2. Electromagnetics: RF Safety
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