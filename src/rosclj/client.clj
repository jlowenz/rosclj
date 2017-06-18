(ns rosclj.client
  (:require [taoensso.timbre :as log]
            [rosclj.rosout :as roslog]))


(defn advertise
  "Advertise the given topic (str) as topic-type (str,
  \"sensor_msgs/LaserScan\"). Returns a publisher object that can be
  used publish messages and otherwise control this topic."
  ([node topic topic-type] (advertise topic topic-type false))
  ([node topic topic-type latch]
   ;; do something here :-\
   ))
