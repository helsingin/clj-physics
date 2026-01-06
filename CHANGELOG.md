# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.1] - 2026-01-06

### Fixed
- **Documentation:** Fixed `cljdoc.edn` to correctly list the new Field Operations manuals in the documentation sidebar.
- **Build:** Updated `Makefile` to strictly sync the SCM `<tag>` field in `pom.xml` to the git tag (e.g., `v0.3.1`), eliminating ambiguity for release tooling.

## [0.3.0] - 2026-01-06

### Added
- **Field Operations (Survival Mode):** Added a new `physics.ops` layer designed for high-stakes operational environments where system stability and risk awareness are paramount.
    - **Kinematics:** Risk-aware propagation (`physics.ops.kinematics`) that automatically grows uncertainty bubbles ($E \sim t^2$) and clamps wild inputs (e.g., 100g acceleration glitches).
    - **Intercepts:** Numerically stable solvers (`physics.ops.intercept`) for Time-To-Go, Closure Rate, and Lead Angle, featuring automatic "Pure Pursuit" fallback and Citardauq quadratic stability.
    - **Safety:** Conservative constraint checkers (`physics.ops.safety`) that fail if *uncertainty bubbles* overlap or breach boundaries, not just nominal positions. Includes 3D Prism checks (Polygon + Altitude).
    - **Uncertainty:** Worst-case error bounding models (`physics.ops.uncertainty`) including linear/quadratic growth and geometric buffer inflation.
    - **Assignment:** Tactical logic (`physics.ops.assignment`) for cost-matrix evaluation and sector assignment using Navigation Standard (0=North).

## [0.2.0] - 2026-01-04

### Added
- **State Estimation:** Introduced `physics.observer.ekf` implementing an Extended Kalman Filter.
    - **Jacobian:** Numerical differentiation of 6-DOF dynamics.
    - **Linear Algebra:** Minimal matrix math library in `physics.math.linear`.
    - **Filter:** Predict/Update steps for full state and covariance estimation.

## [0.1.5] - 2026-01-04

### Added
- **Validation:** Added runtime schema enforcement to `physics.dynamics` and `physics.electromagnetics.fields` using `malli`. This helps catch unit errors (e.g., missing `-kg` suffix) early.
- **Documentation:** Added `CONTRIBUTING.md` to standardize the development workflow.

## [0.1.4] - 2026-01-04

### Changed
- **API:** Unified physical units across the entire library. All physical parameters in maps now have explicit unit suffixes (e.g., `:mass-kg`, `:span-m`, `:density-kg-per-m3`, `:pressure-pa`). This is a breaking change for existing models and configurations.
- **Integrators:** Added event detection support to `rk4` and `rkf45`. Simulations can now terminate early when a condition (e.g., impact) is met.

### Fixed
- **CFD:** Corrected validation logic for 2D geometries.

## [0.1.3] - 2026-01-04

### Fixed
- **Build:** Added missing `org.clojure/tools.logging` dependency to `pom.xml`, resolving API documentation build failures on Cljdoc.

## [0.1.2] - 2026-01-04

### Fixed
- **Dynamics:** Resolved a critical bug in `airframe-forces` where altitude was negated, causing incorrect atmospheric property lookups.
- **Dynamics:** Fixed variable shadowing in `aerodynamic-forces` where the pitch rate `q` was overwriting the dynamic pressure `q`, resulting in zero lift/drag in many scenarios.
- **CFD:** Corrected Gaussian plume normalization constant from $\sqrt{2\pi}$ to $2\pi$.
- **Integrators:** Added an underflow check to the `rkf45` adaptive integrator to prevent potential infinite loops on stiff equations.

### Changed
- **Documentation:** Significantly expanded `README.md` to reflect the project's surrogate modeling philosophy and multi-domain capabilities.
- **Tests:** Updated `observer-test` to use a more stable time step (0.01s) and realistic assertions now that aerodynamic forces are correctly active.
- **Tests:** Added `dynamics-fix-test` to verify 6-DOF force accuracy and altitude-dependent density lookups.

## [0.1.1] - 2026-01-04
- Initial release with core physics, EM, and CFD surrogate modules.
