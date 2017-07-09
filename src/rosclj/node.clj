(ns rosclj.node
  (:require [rosclj.util :as u]
            [rosclj.rosout :as roslog]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]))

(def ^:dynamic *default-master-uri* nil)

(defrecord Node [status master-uri namespace name remapped publications])

(defn make-ros-node
  "Construct a new ROS node. Requires the name parameter, and
  optionally the following params:

  * master-uri
  * anonymous
  * cmd-line-args"
  [name & {:keys [master-uri
                  anonymous
                  cmd-line-args]
           :or   {master-uri      nil
                  anonymous       false
                  cmd-line-args   (next *command-line-args*)}}]
  
  (let [master-uri (when-not master-uri
                     (or *default-master-uri*
                         (u/get-env "ROS_MASTER_URI")))
        master-uri (if (string? master-uri) (u/make-uri master-uri) master-uri)
        node (->Node :shutdown master-uri nil nil {} nil)
        [node params] (u/parse-command-line-args node cmd-line-args)
        _ (u/ensure-directories-exist (:roslog node))]
    (roslog/setup-logging (u/get-ros-log-location node))
    ;; process the params
    ;; start up the node, creating the xmlrpc node
    ))

(defn spin
  "Spin the event loop for the given ROS node. Does not return."
  [node]
  nil)

(defn async-spin
  "Spin the event loop for the ROS node asynchronously. Returns immediately."
  [node]
  nil)

(defn shutdown
  "Shutdown the given node"
  [node])
