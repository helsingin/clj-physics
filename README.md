# clj-physics

An idiomatic Clojure toolkit for multi-domain physics modeling. It brings together coordinate frames, environmental models, vehicle dynamics, constraints, integrators/observers, CFD helpers, electromagnetics, and spatial utilities under a single `physics.*` namespace tree.

## Features
- Core math: vectors, quaternions, rotation/transform helpers (`physics.core`).
- Spatial: poses, frames, geometry/topology utilities (`physics.spatial.*`).
- Frames: WGS84, geodetic↔ECEF/ENU transforms, Earth constants (`physics.frames`).
- Environment: ISA atmosphere, seawater properties, gravity (`physics.environment`).
- Dynamics: airframe, ground, maritime, subsurface, orbital dynamics; force/moment models (`physics.dynamics`).
- Constraints: envelopes for speed/Mach/G/bank/depth (`physics.constraints`).
- Integrators: RK4 and RKF45 adaptive integrators (`physics.integrators`).
- Observer: trajectory projection and synthetic telemetry generation (`physics.observer`).
- Models: representative platforms (fixed-wing, UGV, USV, submarine, Earth) (`physics.models.common`).
- CFD: geometry/environment schemas, surrogate+corrector pipeline, plume/maritime/debris helpers (`physics.cfd.*`).
- Electromagnetics: robust complex math, materials, fields, propagation, constraints (`physics.electromagnetics.*`).

## Install
Add this library to your `deps.edn` via its git URL and tag/SHA when you publish it.

## Quick Start
```clojure
(require '[physics.electromagnetics.fields :as f]
         '[physics.electromagnetics.materials :as m]
         '[physics.electromagnetics.propagation :as p]
         '[physics.frames :as frames]
         '[physics.environment :as env]
         '[physics.dynamics :as dyn])

;; Build a field and propagate it
(def field (f/->field {:type :electric :frequency-hz 2.4e9 :amplitude 1.0 :orientation [0 0 1]}))
(def air (m/->material {:name "air" :type :dielectric :permittivity-relative 1.0006 :permeability-relative 1.0 :conductivity 0.0}))
(p/propagate-plane-wave field air 100.0)

;; Frame transforms
(def origin {:position [37.62 -122.38 0.0]})
(frames/geodetic->ecef {:lat 37.62 :lon -122.38 :alt 0.0})

;; Dynamics: compute derivatives for a fixed-wing state
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

## Logging
A simple logback config lives in `dev/logback.xml`. SLF4J binding is pulled in test/dev via the `:test` alias; production users can supply their own binding or exclude it.

## License
Licensed under GPL-3.0-only. See `LICENSE` for details.
