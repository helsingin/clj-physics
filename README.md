# clj-physics

**A Data-Oriented Toolkit for Multi-Domain Surrogate Modeling & Simulation**

`clj-physics` is a high-assurance, idiomatic Clojure library designed for modeling, simulation, and synthetic data generation. 

Unlike traditional finite-element solvers that prioritize microscopic fidelity at the cost of computational expense, `clj-physics` focuses on **surrogate modeling**, **fast-time simulation**, and **uncertainty quantification**. It provides a unified data-driven interface for rigid-body dynamics (6-DOF), computational fluid dynamics (CFD) approximations, and electromagnetic (EM) propagation.

## Philosophy

The library is built on three core principles:

1.  **Data as Physics:** All physical entities—from airframes and material properties to flow fields and RF signals—are represented as immutable, schema-validated maps. This facilitates transparent introspection and easier integration with machine learning pipelines.
2.  **Surrogate & Correct:** For complex phenomena like fluid flow, the library employs a "Surrogate + Corrector" pipeline. It generates fast initial estimates using analytical models (or hooks for ML inference) and then enforces physical plausibility (mass conservation) using a divergence-free projection solver.
3.  **Numerical Robustness:** The system prioritizes stability over raw speed. Critical arithmetic operations, particularly in electromagnetics, are guarded against underflow/overflow and ill-conditioned states, falling back to `BigDecimal` precision when necessary.

## Key Capabilities

### 🌊 Surrogate CFD
Avoids the computational cost of full Navier-Stokes solvers by using reduced-order models suitable for mission planning and real-time synthesis.
*   **Helmholtz-Hodge Decomposition:** Implements a grid-based **Poisson solver** (Conjugate Gradient method) to project analytical flow fields (like Gaussian plumes) onto a divergence-free manifold, ensuring mass conservation and boundary enforcement.
*   **Domain Wrappers:** Specialized APIs for **Atmospheric Plumes** (dispersion modeling), **Maritime/Shallow-Water** (wave fields), and **Debris Transport**.
*   **ML Integration:** A registry system allows the injection of external machine learning models (e.g., ONNX) to serve as the surrogate prior.

### 📡 Electromagnetics
A robust engine for modeling RF propagation, safety envelopes, and material interaction.
*   **Stochastic Propagation:** Built-in Monte Carlo engine for propagating plane waves through media with statistical uncertainty.
*   **Phasor Arithmetic:** Full support for harmonic fields ($e^{j\omega t}$ convention), superposition, and polarization.
*   **Safety Constraints:** Automated evaluation of power density ($W/m^2$) and field amplitude limits.

### ✈️ Dynamics & Environment
A complete 6-Degrees-of-Freedom (6-DOF) simulation stack.
*   **Integrators:** Adaptive **Runge-Kutta-Fehlberg (RKF45)** and classic RK4 integrators.
*   **Platform Models:** Validated models for **Fixed-Wing Aircraft**, **UGVs**, **USVs**, and **Submarines**.
*   **Environment:** High-fidelity WGS84 coordinate transforms, **1976 US Standard Atmosphere** (up to 86km), and **UNESCO 1983 Ocean Equation of State**.

## Install

Pull from Clojars:

```clojure
;; deps.edn
net.clojars.helsingin/physics {:mvn/version "0.1.4"}
```

## Quick Start

```clojure
(require '[physics.electromagnetics.fields :as f]
         '[physics.electromagnetics.materials :as m]
         '[physics.electromagnetics.propagation :as p]
         '[physics.frames :as frames]
         '[physics.environment :as env]
         '[physics.dynamics :as dyn])

;; 1. Build a field and propagate it
(def field (f/->field {:type :electric :frequency-hz 2.4e9 :amplitude 1.0 :orientation [0 0 1]}))
(def air (m/->material {:name "air" :type :dielectric :permittivity-rel 1.0006 :conductivity-s-m 0.0}))
(p/propagate-plane-wave field air 100.0)

;; 2. Frame transforms (WGS84 <-> ECEF)
(def origin {:position [37.62 -122.38 0.0]})
(frames/geodetic->ecef {:lat-deg 37.62 :lon-deg -122.38 :alt-m 0.0})

;; 3. Dynamics: compute derivatives for a fixed-wing state
(def model (physics.models.common/fixed-wing))
(def state {:position [0 0 1000]
            :velocity [60 0 0]
            :orientation (physics.core/euler->quaternion {:roll 0 :pitch 0 :yaw 0})
            :angular-rate [0 0 0]})
(dyn/rigid-body-derivatives model state (env/isa-profile 1000) {:throttle 0.5})
```

## Tests

```bash
clojure -M:test
```
Tests cover core math, frames, environment, dynamics, constraints, integrators, observer, CFD, electromagnetics, and spatial utilities. A default `tests.edn` is included for Kaocha with a pinned seed.

## Build and release
- Build the jar: `clojure -T:build jar`
- Deploy to Clojars: `make deploy` (reads credentials from `~/.m2/settings.xml` server `clojars`, or falls back to `CLOJARS_USERNAME`/`CLOJARS_PASSWORD` if exported)

## License
Licensed under GPL-3.0-only. See `LICENSE` for details.