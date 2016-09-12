(ns rosclj.node
  (:require [rosclj.util :as u]))

(defrecord Node [status master-uri namespace name remapped-names])

(defn make-ros-node
  "Construct a new ROS node. Requires the name parameter, and
  optionally the following params:

  * master-uri
  * anonymous
  * cmd-line-args"
  [name & {:keys [master-uri
                  anonymous
                  cmd-line-args]
           :or   {master-uri      (u/make-uri "127.0.0.1"
                                              11311)
                  anonymous       false
                  cmd-line-args   (next *command-line-args*)}}]
  (let [master-uri (if (string? master-uri) (u/make-uri master-uri) master-uri)]
    (->Node :shutdown master-uri "" "" {})))

(defn start
  "Start the given ROS node"
  [node]
  ())

(defn shutdown
  "Shutdown the given node"
  [node])