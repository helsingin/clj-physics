# CFD Corrector Performance Notes

This document captures the current performance posture of the CFD corrector, how to measure it, and how to tune it for different use cases.

## Measurement

- Harness: `dev/dev/cfd_perf.clj`
- Run (from repo root):
  ```
  clj -X dev.cfd-perf/run :sizes '[32 64 100]' :runs 2 :iterations 20 :compare-parallel? true
  ```
- Options:
  - `:sizes` — grid edge lengths (nx = ny = nz)
  - `:runs` — samples per size
  - `:iterations` — CG iterations in the Poisson solve (runtime scales ~linearly)
  - `:compare-parallel?` — run both serial and `:parallel? true`
  - `:dimensions` — 2 or 3 (default 3)

## Current numbers (runs=2, iterations=20, 3D, Apple M4 Max 16c/16t, 128 GB)

- 32³: serial ~246 ms; parallel ~141 ms (Poisson ~94 ms)
- 64³: serial ~1093 ms; parallel ~423 ms (Poisson ~151 ms)
- 100³: serial ~4351 ms; parallel ~1668 ms (Poisson ~624 ms)

Parallel is opt-in (`{:parallel? true}`) and auto-disables on tiny problems (n <= 150k interior cells).

## Knobs and trade-offs

- Iterations: Fewer CG iterations reduce time linearly; trade accuracy. For interactive use, try 10–20 iterations; for higher fidelity, 40+.
- Grid size: Time scales ~O(n³). Downsample grids for higher Hz.
- Parallel: Enable `:parallel? true` in `corrector/correct` for 64³ and larger. Small grids may not benefit.
- Surrounding work: The corrector still does divergence/gradient/boundary passes on maps; heavy map-to-array conversions remain. Further gains would come from flattening more of the pipeline or SIMD/GPU paths.

## “Good enough” guidance by use case

- Robotics / autonomy planning (5–20 Hz budget):
  - 32³ at 10–20 iterations can approach ~5–10 Hz in parallel.
  - 64³ parallel ~0.4 s → ~2–3 Hz; reduce iterations or grid to hit higher rates.
- Interactive tuning / UI (1–5 Hz): 64³–100³ parallel is acceptable for human-in-loop iteration.
- Real-time (60 Hz): Not feasible on pure Clojure/Java arrays; would require SIMD/accelerated native kernels or coarser grids with very few iterations.

## Tuning checklist

- Reduce `:iterations` for faster but less accurate projections.
- Set `:parallel? true` for grids >= ~64³.
- Consider coarser grids or subregion solves for higher update rates.
- Use the perf harness before/after changes to quantify impact.
