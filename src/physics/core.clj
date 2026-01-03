(ns physics.core
  "Shared physics utilities: rotational algebra, vector helpers, constants."
  (:require [clojure.math :as math]))

(def ^:const pi Math/PI)
(def ^:const two-pi (* 2.0 pi))
(def ^:const deg->rad (/ pi 180.0))
(def ^:const rad->deg (/ 180.0 pi))

(defn clamp [x min-val max-val]
  (-> x (max min-val) (min max-val)))

(defn magnitude [v]
  (math/sqrt (reduce + (map #(* % %) v))))

(defn normalize
  "Return unit vector in direction of v."
  [v]
  (let [mag (magnitude v)]
    (if (zero? mag)
      (vec v)
      (mapv #(/ % mag) v))))

(defn dot [a b]
  (reduce + (map * a b)))

(defn cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by))
   (- (* az bx) (* ax bz))
   (- (* ax by) (* ay bx))])

(defn matmul [m v]
  (mapv (fn [row] (dot row v)) m))

(defn transpose [m]
  (apply mapv vector m))

(defn euler->rot-matrix
  "Return body→inertial rotation matrix for ZYX (yaw-pitch-roll) angles." 
  [{:keys [roll pitch yaw]}]
  (let [cphi (math/cos roll)
        sphi (math/sin roll)
        ctheta (math/cos pitch)
        stheta (math/sin pitch)
        cpsi (math/cos yaw)
        spsi (math/sin yaw)]
    [[(* cpsi ctheta)
      (- (* cpsi stheta sphi) (* spsi cphi))
      (+ (* cpsi stheta cphi) (* spsi sphi))]
     [(* spsi ctheta)
      (+ (* spsi stheta sphi) (* cpsi cphi))
      (- (* spsi stheta cphi) (* cpsi sphi))]
     [(- stheta)
      (* ctheta sphi)
     (* ctheta cphi)]]))

(defn euler->quaternion
  [{:keys [roll pitch yaw]}]
  (let [cr (math/cos (* 0.5 roll))
        sr (math/sin (* 0.5 roll))
        cp (math/cos (* 0.5 pitch))
        sp (math/sin (* 0.5 pitch))
        cy (math/cos (* 0.5 yaw))
        sy (math/sin (* 0.5 yaw))]
    [(+ (* cy cp cr) (* sy sp sr))
     (+ (* cy cp sr) (* sy sp cr))
     (- (* cy sp cr) (* sy cp sr))
     (+ (* sy cp cr) (* cy sp sr))]))

(defn body->inertial
  [attitude]
  (euler->rot-matrix attitude))

(defn inertial->body [attitude]
  (transpose (body->inertial attitude)))

(defn quaternion-derivative
  "Return quaternion derivative given angular rate vector (rad/s)."
  [[qw qx qy qz] [p q r]]
  (let [half 0.5]
    [(* half (- 0 (* qx p) (* qy q) (* qz r)))
     (* half (+ (* qw p) (* qy r) (- (* qz q))))
     (* half (+ (* qw q) (* qz p) (- (* qx r))))
     (* half (+ (* qw r) (* qx q) (- (* qy p))))]))

(defn quaternion-normalize [[w x y z]]
  (let [mag (math/sqrt (+ (* w w) (* x x) (* y y) (* z z)))]
    (if (zero? mag)
      [1.0 0.0 0.0 0.0]
      [(/ w mag) (/ x mag) (/ y mag) (/ z mag)])))

(defn quaternion->dcm
  [[w x y z]]
  [[(- 1.0 (* 2.0 (+ (* y y) (* z z))))
    (* 2.0 (+ (* x y) (* w z)))
    (* 2.0 (- (* x z) (* w y)))]
   [(* 2.0 (- (* x y) (* w z)))
    (- 1.0 (* 2.0 (+ (* x x) (* z z))))
    (* 2.0 (+ (* y z) (* w x)))]
   [(* 2.0 (+ (* x z) (* w y)))
    (* 2.0 (- (* y z) (* w x)))
    (- 1.0 (* 2.0 (+ (* x x) (* y y))))]])

(defn integrate-quaternion
  [q angular-rate dt]
  (quaternion-normalize
   (mapv + q (mapv #(* dt %) (quaternion-derivative q angular-rate)))))
