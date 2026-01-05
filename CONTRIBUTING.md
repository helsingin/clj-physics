# Contributing to clj-physics

We welcome contributions! To ensure consistency and stability, please adhere to the following guidelines.

## Development Workflow

1.  **Branching:** Create a feature branch for your changes (e.g., `feat/new-integrator` or `fix/plume-normalization`).
2.  **Testing:** Ensure all tests pass. We rely on deterministic tests with pinned seeds.
    ```bash
    clojure -M:test
    ```
3.  **Validation:** Use `malli` schemas for all new data structures. This library prioritizes runtime correctness and "fail-fast" behavior.

## Documentation & Versioning

If your changes affect the public API or fix a bug:

1.  **Version Bump:** Update the version number in `version.txt`.
    *   **Patch (x.y.Z):** Bug fixes, minor improvements.
    *   **Minor (x.Y.z):** New features, non-breaking changes.
    *   **Major (X.y.z):** Breaking API changes (e.g., unit renaming).
2.  **README:** Ensure the installation version in `README.md` matches `version.txt`.
3.  **Changelog:** Add an entry to `CHANGELOG.md` under `[Unreleased]` (or the new version number if preparing a release). Follow the "Keep a Changelog" format.

## Releasing

Releases are handled via `make`. See [docs/releasing.md](docs/releasing.md) for the detailed checklist.

## Design Philosophy

*   **Data First:** Prefer maps and schemas over protocols and records.
*   **Zero Dependencies:** Avoid adding runtime dependencies (especially native ones) unless absolutely necessary for performance (e.g., SIMD).
*   **Units:** All physical keys MUST have an SI unit suffix (e.g., `:mass-kg`, `:time-s`, `:pressure-pa`).
