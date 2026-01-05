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

## Completed in v0.1.5
### Polish & Schema Enforcement
- **SIMD/Vectorization:** Port the CFD Poisson solver (`physics.cfd.corrector`) to `dtype-next` or `tech.ml.dataset` for large-grid performance.
