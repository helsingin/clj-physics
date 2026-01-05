(ns physics.math.linear
  "Minimal linear algebra for state estimation (pure Clojure)."
  (:require [clojure.math :as math]
            [physics.core :as core]))

(defn zeros [rows cols]
  (vec (repeat rows (vec (repeat cols 0.0)))))

(defn identity-mat [n]
  (vec (map-indexed (fn [i _]
                      (assoc (vec (repeat n 0.0)) i 1.0))
                    (range n))))

(defn transpose [m]
  (apply mapv vector m))

(defn mat-mul [a b]
  (let [rows-a (count a)
        cols-a (count (first a))
        rows-b (count b)
        cols-b (count (first b))]
    (when (not= cols-a rows-b)
      (throw (ex-info "Matrix dimension mismatch" {:a [rows-a cols-a] :b [rows-b cols-b]})))
    (let [b-t (transpose b)]
      (vec (for [row a]
             (vec (for [col b-t]
                    (core/dot row col))))))))

(defn mat-add [a b]
  (mapv (fn [r1 r2] (mapv + r1 r2)) a b))

(defn mat-sub [a b]
  (mapv (fn [r1 r2] (mapv - r1 r2)) a b))

(defn scalar-mul [m s]
  (mapv (fn [row] (mapv #(* % s) row)) m))

(defn- pivot-row [m col-idx]
  (apply max-key #(Math/abs (double (nth % col-idx))) m))

(defn invert
  "Invert a square matrix using Gauss-Jordan elimination."
  [m]
  (let [n (count m)]
    (when (not= n (count (first m)))
      (throw (ex-info "Matrix must be square" {:dims [(count m) (count (first m))]})))
    (let [aug (mapv (fn [row i] (vec (concat row (nth (identity-mat n) i))))
                    m (range n))
          solved (loop [curr aug
                        i 0]
                   (if (= i n)
                     curr
                     (let [pivot-idx (apply max-key #(Math/abs (double (nth % i)))
                                            (map-indexed vector (subvec curr i)))
                           pivot-row-idx (+ i (first pivot-idx))
                           pivot-val (nth (nth curr pivot-row-idx) i)]
                       (if (zero? pivot-val)
                         (throw (ex-info "Matrix is singular" {:matrix m}))
                         (let [swapped (assoc curr i (nth curr pivot-row-idx)
                                              pivot-row-idx (nth curr i))
                               norm-row (mapv #(/ % pivot-val) (nth swapped i))
                               cleared (map-indexed
                                        (fn [idx row]
                                          (if (= idx i)
                                            norm-row
                                            (let [factor (nth row i)]
                                              (mapv #(- %1 (* factor %2)) row norm-row))))
                                        swapped)]
                           (recur cleared (inc i)))))))]
      (vec (map #(subvec % n) solved)))))
