(ns rosclj.rosout
  (:require [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]))

(def ^:dynamic *ros-log-configured* false)
(def ^:dynamic *ros-log-path* nil)
(def ^:dynamic *ros-log-stream* nil)

(defn setup-logging [path]
  (when-not *ros-log-configured*
    ;; set up Timbre logging!
    (set! *ros-log-path* path)
    (log/merge-config!
      {:appenders {:spit (appenders/spit-appender
                           {:fname *ros-log-path*})}})
    (set! *ros-log-configured* true)))

(defn can-write-to-log []
  (and *ros-log-stream* ))