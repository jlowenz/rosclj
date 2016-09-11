(ns rosclj.time)

(defrecord Time [^long secs ^long nsecs])
(defrecord Duration [^long secs ^long nsecs])