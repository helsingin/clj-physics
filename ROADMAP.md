# Roadmap

This document outlines the development trajectory for `clj-physics`.

## Completed in v0.1.4
### 1. Unified Units of Measure
**Goal:** Explicitly suffix keys with units to strictly enforce SI conventions and prevent conversion errors.
- [x] Rename schema keys in `physics.cfd.core` (e.g., `:dx` -> `:dx-m`).
- [x] Update platform models in `physics.models.common` (e.g., `:mass` -> `:mass-kg`).
- [x] Update EM propagation signatures.

### 2. Integrator Event Detection
**Goal:** Allow simulations to stop exactly when a physical condition is met (e.g., altitude <= 0).
- [x] Add `:events` predicate support to `physics.integrators/rk4` and `rkf45`.
- [x] Implement linear interpolation for precise event time isolation.

## v0.1.5 (Completed)
### Polish & Schema Enforcement
- [x] Add runtime malli validation to `physics.dynamics`.
- [x] Add runtime malli validation to `physics.electromagnetics`.
- [x] Standardize contribution guidelines in `CONTRIBUTING.md`.

## Completed in v0.2.0
### State Estimation
- [x] **EKF (Extended Kalman Filter):** Implement standard estimators using the existing `rigid-body-derivatives` for the Jacobian.

## Completed in v0.3.0
### 🛡️ Field Operations (Survival Mode)
**Goal:** A high-resilience tactical layer for "messy" environments prioritizing system stability and worst-case bounding.
- [x] **Risk-Aware Kinematics:** CV/CA models that automatically propagate uncertainty bubbles ($E \sim t^2$).
- [x] **Tactical Intercepts:** Numerically stable solvers with automatic "Pure Pursuit" fallbacks.
- [x] **Conservative Safety:** Constraint checkers (Separation, 3D Prisms) that respect uncertainty margins.
- [x] **Tactical Assignment:** Robust cost-matrices and Navigation Standard (0=North) sector mapping.

## Future Considerations
### Performance
- **SIMD/Vectorization:** Port the CFD Poisson solver (`physics.cfd.corrector`) to `dtype-next` or `tech.ml.dataset` for large-grid performance.
