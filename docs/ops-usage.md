# Field Operations Physics: Usage Guide

This library (`physics.ops.*`) is a **High-Assurance** toolkit designed for **Operational Resilience** ("Survival Mode"). It prioritizes system stability, worst-case error bounding, and graceful degradation over strict correctness when inputs are messy.

## Core Philosophy

1.  **Never Crash:** Functions return status codes (e.g., `:status :degraded`) or logical fallbacks instead of throwing exceptions.
2.  **Distrust Data:**
    *   **NaNs** trigger immediate fallback to safer models.
    *   **Latencies** > 2.0s are clamped.
    *   **Inputs** are auto-normalized to 3D vectors (`[x y]` -> `[x y 0.0]`).
3.  **Worst-Case Bounding:** Safety checks fail if *error bubbles* overlap, not just center-points.
4.  **Temporal Truth:** Large time-steps are iterated in 10s chunks to ensure accurate path integration.

---

## 1. Kinematics (Motion Prediction)
**Namespace:** `physics.ops.kinematics`

Handles state propagation with integrated uncertainty growth.

```clojure
(require '[physics.ops.kinematics :as k])

(def state 
  {:position [0 0 0] :velocity [10 0 0] :acceleration [1.0 0 0]
   ;; Uncertainty: 5m pos, 1m/s vel, 0.5m/s^2 unknown maneuver
   :uncertainty {:pos 5.0 :vel 1.0 :acc-unknown 0.5}
   ;; Limits: Clamp velocity to max-speed
   :limits {:max-speed 300.0}})

;; Propagate 5 seconds forward
(def future (k/propagate state 5.0))
;; => Updates :position, :velocity, AND expands :uncertainty (:pos)
```

*   **`propagate`**: Automatic CV/CA selection. Clamps `dt` (max 10s steps) and `acceleration` (max 100g).
*   **`compensate-latency`**: Fast-forwards stale states (clamped to 2.0s max latency).

---

## 2. Intercepts (Navigation)
**Namespace:** `physics.ops.intercept`

Solvers for relative motion, using stable algorithms (Citardauq) to prevent precision loss.

```clojure
(require '[physics.ops.intercept :as int])

;; Time-To-Go with Speed Limit
(int/time-to-go p-i p-t speed {:max-speed 20.0})

;; Probabilistic Intercept Window
(int/intercept-bounds p-i s-i p-t v-t target-error-radius)
;; => {:min-time 9.5 :max-time 10.5 :nominal-time 10.0 ...}

;; Guidance Generator
(int/guidance :lead p-i s-i p-t v-t)
;; Returns {:aim-vector ... :status :valid} OR falls back to {:status :fallback-to-pure}
```

---

## 3. Safety (Constraints & Limits)
**Namespace:** `physics.ops.safety`

Conservative checks that respect uncertainty and static buffers.

### Dynamic Limits
```clojure
(require '[physics.ops.safety :as safe])

(safe/check-dynamics state {:max-speed 30.0 :max-turn-rate 0.5})
;; => #{:speed-limit :turn-limit} or nil
```

### Predictive Safety (Collision Avoidance)
Checks if *uncertainty bubbles* overlap, not just nominal positions.
```clojure
(safe/conservative-predictive-safety? 
  state-a state-b unc-a unc-b 
  {:min-separation 100.0} 
  {:horizon 30.0 :dt 1.0})
```

### Boundary Enforced (Prisms)
Checks if an asset stays inside a 3D volume (Polygon + Altitude).
```clojure
(safe/conservative-predictive-boundary?
  state uncertainty 
  polygon-vertices 
  {:min-alt 100 :max-alt 500 :margin 10.0} ;; Respects static margin
  {:horizon 10.0})
```

---

## 4. Uncertainty (Risk Models)
**Namespace:** `physics.ops.uncertainty`

Helper models for error estimation.

*   **`linear-error-growth`**: Simple $R(t) = R_0 + \alpha t$ model.
*   **`inflate-geometry`**: Wraps `:point`, `:path`, or `:polygon` with a `:margin`.
*   **`ttg-bounds`**: Calculates arrival window considering both position error and speed variance ($\pm \Delta v$).

---

## 5. Assignment (Tactics)
**Namespace:** `physics.ops.assignment`

Geometry-based sorting and filtering.

```clojure
(require '[physics.ops.assignment :as assign])

;; 1. Cost Matrix (Handles missing data -> Infinity)
(assign/cost-matrix candidates {:fuel -1.0 :ttg 2.0})

;; 2. Nearest Neighbor (Squared distance)
(assign/nearest-neighbor [0 0 0] candidates)

;; 3. Sector Assignment (0=North, CW)
(assign/assign-sectors targets center [{:id :north :min-az 315 :max-az 45}])

;; 4. Feasibility Filter (Safe-fail)
(assign/filter-feasible candidates (fn [c] (< (:fuel c) 10.0)))
```

---

## Numerical Safety Reference
*   **Epsilon:** `1.0e-9`
*   **Input Handling:** All 2D vectors `[x y]` are promoted to 3D `[x y 0.0]`.
*   **Frame Validation:** Explicitly checks `:frame` metadata compatibility.
