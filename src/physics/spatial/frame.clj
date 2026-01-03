(ns physics.spatial.frame
  "Coordinate frame primitives and rigid transforms." 
  (:import [java.lang Math]))

(def identity-quaternion
  "Unit quaternion representing no rotation."
  [1.0 0.0 0.0 0.0])

(defn normalize-quaternion
  "Normalize quaternion Q to unit length."
  [[w x y z :as q]]
  (let [mag (Math/sqrt (+ (* w w) (* x x) (* y y) (* z z)))]
    (if (zero? mag)
      identity-quaternion
      (mapv #(/ % mag) q))))

(defn ->transform
  "Create a transform map ensuring proper defaults."
  [{:keys [translation rotation frame]
    :or {translation [0.0 0.0 0.0]
         rotation identity-quaternion}}]
  {:translation (vec translation)
   :rotation (normalize-quaternion rotation)
   :frame frame})

(defn identity-transform []
  (->transform {}))

(defn- quat-mul [[w1 x1 y1 z1] [w2 x2 y2 z2]]
  [(+ (* w1 w2) (- (* x1 x2)) (- (* y1 y2)) (- (* z1 z2)))
   (+ (* w1 x2) (* x1 w2) (* y1 z2) (- (* z1 y2)))
   (+ (* w1 y2) (- (* x1 z2)) (* y1 w2) (* z1 x2))
   (+ (* w1 z2) (* x1 y2) (- (* y1 x2)) (* z1 w2))])

(defn- quat-conj [[w x y z]]
  [w (- x) (- y) (- z)])

(defn- vec->quat [[vx vy vz]]
  [0.0 vx vy vz])

(defn rotate-vector
  "Rotate vector V with quaternion Q."
  [q v]
  (let [[_ rx ry rz] (quat-mul (quat-mul q (vec->quat v)) (quat-conj q))]
    [rx ry rz]))

(defn transform-point
  "Apply TRANSFORM to POINT (3-vector)."
  [{:keys [translation rotation]} point]
  (let [rotated (rotate-vector rotation point)]
    (mapv + rotated translation)))

(defn transform-vector
  "Rotate vector using the transform without applying translation."
  [{:keys [rotation]} v]
  (rotate-vector rotation v))

(defn compose
  "Compose transforms T1 followed by T2."
  [t1 t2]
  (let [r1 (:rotation t1)
        r2 (:rotation t2)
        t1-trans (:translation t1)
        t2-trans (:translation t2)
        new-rot (normalize-quaternion (quat-mul r1 r2))
        rotated-trans (transform-vector t1 t2-trans)
        new-trans (mapv + t1-trans rotated-trans)]
    {:translation new-trans
     :rotation new-rot
     :frame (:frame t1)}))

(defn quaternion-dot [[w1 x1 y1 z1] [w2 x2 y2 z2]]
  (+ (* w1 w2) (* x1 x2) (* y1 y2) (* z1 z2)))

(defn slerp
  "Spherical linear interpolation between q0 and q1 by factor t in [0,1]."
  [q0 q1 t]
  (let [q0 (normalize-quaternion q0)
        q1 (normalize-quaternion q1)
        dot (double (quaternion-dot q0 q1))
        [q1 dot] (if (< dot 0.0)
                   [(mapv - q1) (- dot)]
                   [q1 dot])]
    (if (> dot 0.9995)
      (normalize-quaternion
       (mapv + q0 (mapv #(* t %) (mapv - q1 q0))))
      (let [theta0 (Math/acos (Math/min 1.0 (Math/max -1.0 dot)))
            sin-theta0 (Math/sin theta0)
            theta (* theta0 t)
            sin-theta (Math/sin theta)
            s0 (/ (Math/sin (- theta0 theta)) sin-theta0)
            s1 (/ sin-theta sin-theta0)]
        (normalize-quaternion
         (mapv + (mapv #(* s0 %) q0)
                (mapv #(* s1 %) q1)))))))
