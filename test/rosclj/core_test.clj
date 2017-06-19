(ns rosclj.core-test
  (:use midje.sweet)
  (:require [taoensso.timbre :as log]
            [manifold.deferred :as d]
            [rosclj.core :refer :all]
            [rosclj.tcpros :as tcp]))

(def port 7778)

(fact "header parse works"
      (tcp/parse-tcpros-header {:header ["a=b" "c=d"]}) => {"a" "b"
                                                            "c" "d"})

(fact "creating a super simple handler for tcp server"
      (let [h (fn [so info]
                (d/loop []
                  (log/warn "handler loop")
                  (->
                   (d/let-flow [msg (tcp/read! so)]
                     (log/warn "blah:" msg)
                     (when-not (= :empty msg)
                       (d/let-flow [msg' (d/future msg)]
                         (log/warn "GOT: " msg')
                         (when-not (= :empty msg')
                           (d/recur)))))
                   (d/catch (fn [ex] (tcp/close! so))))))
            serv (tcp/make-ros-server h port)
            cli @(tcp/make-ros-client "localhost" port)
            _ (println "client: " cli)]
        (try
          @(tcp/write! cli [:header ["a=b" "c=d"] :message "oogabooga"]) => true
          @(tcp/write! cli [:header ["e=f" "g=h"] :message "blah"]) => true
          (finally (Thread/sleep 1000) (.close serv)))))
