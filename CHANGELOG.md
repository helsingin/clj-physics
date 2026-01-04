# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
