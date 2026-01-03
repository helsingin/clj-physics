(ns physics.spatial.topology
  "Relations and formation primitives built on geometry.")

(defn leader-follower
  "Describe a leader/follower offset relation.
  Required keys: :leader, :follower, :offset (3D vector).
  Optional: :frame, :constraints"
  [{:keys [leader follower offset frame constraints]}]
  (when-not (and leader follower offset (= 3 (count offset)))
    (throw (ex-info "Leader/follower relation requires leader, follower, and 3D offset"
                    {:leader leader :follower follower :offset offset})))
  {:type :leader-follower
   :leader leader
   :follower follower
   :offset (vec offset)
   :frame (or frame :world)
   :constraints constraints})
