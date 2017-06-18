(ns rosclj.rosout
  (:require [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]
            [me.raynes.fs :as fs]))

(def ^:dynamic *ros-log-configured* false)
(def ^:dynamic *ros-log-path* nil)
(def ^:dynamic *ros-log-path-exists* false)

(defn setup-logging [path]
  (when-not *ros-log-configured*
    ;; set up Timbre logging!
    (set! *ros-log-path* path)
    ;; ensure the given path exists
    (when (fs/exists? (fs/parent path))
      (set! *ros-log-path-exists* true))
    (log/merge-config!
     {:appenders {:spit (appenders/spit-appender
                         {:fname *ros-log-path*})}})
    (set! *ros-log-configured* true)))

;; since we're using timbre
(defn can-write-to-log [] *ros-log-path-exists*)

(defn rosout-msg [name level & args])

;; not sure this will be efficient enough - especially since name is unused
;(defn ros-debug [name & args] (apply log/debug args))
;(defn ros-info [name & args] (apply log/info args))
;(defn ros-warn [name & args] (apply log/warn args))
;(defn ros-error [name & args] (apply log/error args))


