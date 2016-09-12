(ns rosclj.rosout)

(def ^:dynamic *ros-log-stream* nil)

(defn can-write-to-log []
  (and *ros-log-stream* ))