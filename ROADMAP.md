# Roadmap

This document outlines the development trajectory for `clj-physics`.

## v0.1.4 (In Progress)
### 1. Unified Units of Measure
**Goal:** Explicitly suffix keys with units to strictly enforce SI conventions and prevent conversion errors.
- [ ] Rename schema keys in `physics.cfd.core` (e.g., `:dx` -> `:dx-m`).
- [ ] Update platform models in `physics.models.common` (e.g., `:mass` -> `:mass-kg`).
- [ ] Update EM propagation signatures.

### 2. Integrator Event Detection
**Goal:** Allow simulations to stop exactly when a physical condition is met (e.g., altitude <= 0).
- [ ] Add `:events` predicate support to `physics.integrators/rk4` and `rkf45`.
- [ ] Implement linear interpolation for precise event time isolation.

## Future Considerations
### State Estimation (v0.2.0)
- **EKF (Extended Kalman Filter):** Implement standard estimators using the existing `rigid-body-derivatives` for the Jacobian.

### Performance
- **SIMD/Vectorization:** Port the CFD Poisson solver (`physics.cfd.corrector`) to `dtype-next` or `tech.ml.dataset` for large-grid performance.
