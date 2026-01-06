(ns physics.ops.pathing
  "Tactical pathfinding solvers (A*, Grid).
   Survival Mode: Bounded iterations, no infinite loops."
  (:require [physics.core :as core]
            [physics.ops.kinematics :as k]))

(defn- reconstruct-path [came-from current]
  (loop [curr current
         path (list current)]
    (if-let [prev (get came-from curr)]
      (recur prev (conj path prev))
      (vec path))))

(defn a-star
  "A* Search.
   start, goal: Nodes (must be map keys).
   neighbors-fn: node -> [{:node n :cost c} ...]
   heuristic-fn: node -> cost
   options: {:max-iter 1000}
   Returns: {:path [nodes] :cost c :status :success/:timeout/:no-path}"
  [start goal neighbors-fn heuristic-fn {:keys [max-iter] :or {max-iter 5000}}]
  (loop [open-set {start {:g 0 :f (heuristic-fn start)}}
         closed-set #{}
         came-from {}
         iter 0]
    (if (> iter max-iter)
      {:status :timeout}
      (if (empty? open-set)
        {:status :no-path}
        (let [current (key (apply min-key (comp :f val) open-set))]
          (if (= current goal)
            {:status :success
             :path (reconstruct-path came-from current)
             :cost (:g (get open-set current))}
            (let [current-g (:g (get open-set current))
                  neighbors (neighbors-fn current)
                  
                  ;; Update both open-set and came-from
                  [next-open next-came-from] 
                  (reduce
                   (fn [[acc-open acc-came] {:keys [node cost]}]
                     (if (contains? closed-set node)
                       [acc-open acc-came]
                       (let [tentative-g (+ current-g cost)]
                         (if (< tentative-g (get-in acc-open [node :g] Double/POSITIVE_INFINITY))
                           [(assoc acc-open node {:g tentative-g 
                                                  :f (+ tentative-g (heuristic-fn node))})
                            (assoc acc-came node current)]
                           [acc-open acc-came]))))
                   [(dissoc open-set current) came-from]
                   neighbors)]
              (recur next-open (conj closed-set current) next-came-from (inc iter)))))))))