(ns physics.frames
  "Reference frame conversions and Earth constants."
  (:require [physics.spatial.pose :as pose]
            [clojure.math :as math]))

(def ^:const wgs84-a 6378137.0)
(def ^:const wgs84-f (/ 1.0 298.257223563))
(def ^:const wgs84-b (* wgs84-a (- 1.0 wgs84-f)))
(def ^:const wgs84-e2 (* 2.0 wgs84-f (- 1.0 wgs84-f)))
(def ^:const earth-rotation 7.2921159e-5)

(defn degrees->radians [deg]
  (* deg (/ Math/PI 180.0)))

(defn radians->degrees [rad]
  (* rad (/ 180.0 Math/PI)))

(defn earth-rotation-rate []
  earth-rotation)

(defn geodetic->ecef
  "Convert geodetic coordinates (deg, deg, meters) to Earth-Centered, Earth-Fixed vector."
  [{:keys [lat lon alt]}]
  (let [lat-r (degrees->radians lat)
        lon-r (degrees->radians lon)
        sin-lat (math/sin lat-r)
        cos-lat (math/cos lat-r)
        sin-lon (math/sin lon-r)
        cos-lon (math/cos lon-r)
        N (/ wgs84-a (math/sqrt (- 1.0 (* wgs84-e2 (math/pow sin-lat 2)))))
        x (* (+ N alt) cos-lat cos-lon)
        y (* (+ N alt) cos-lat sin-lon)
        z (* (+ (* (- 1.0 wgs84-e2) N) alt) sin-lat)]
    [x y z]))

(defn- origin-terms [pose]
  (let [[lat lon alt] (:position pose)
        lat-r (degrees->radians lat)
        lon-r (degrees->radians lon)
        sin-lat (math/sin lat-r)
        cos-lat (math/cos lat-r)
        sin-lon (math/sin lon-r)
        cos-lon (math/cos lon-r)
        origin-ecef (geodetic->ecef {:lat lat :lon lon :alt alt})]
    {:lat-r lat-r
     :lon-r lon-r
     :sin-lat sin-lat
     :cos-lat cos-lat
     :sin-lon sin-lon
    :cos-lon cos-lon
    :ecef origin-ecef}))

(defn ecef->enu
  "Convert ECEF vector into ENU coordinates relative to ORIGIN pose."
  [origin-pose target-ecef]
  (let [{:keys [sin-lat cos-lat sin-lon cos-lon ecef]} (origin-terms origin-pose)
        [x0 y0 z0] ecef
        [xt yt zt] target-ecef
        dx (- xt x0)
        dy (- yt y0)
        dz (- zt z0)
        east (+ (- (* sin-lon dx)) (* cos-lon dy))
        north (+ (* (- sin-lat cos-lon) dx)
                 (* (- sin-lat sin-lon) dy)
                 (* cos-lat dz))
        up (+ (* cos-lat cos-lon dx)
              (* cos-lat sin-lon dy)
              (* sin-lat dz))]
    [east north up]))

(defn geodetic->enu
  "Return ENU pose of target with respect to origin pose (WGS84 inputs)."
  [origin-pose {:keys [lat lon alt]}]
  (let [target-ecef (geodetic->ecef {:lat lat :lon lon :alt alt})
        enu (ecef->enu origin-pose target-ecef)]
    (pose/->pose {:position enu
                  :frame :enu})))
