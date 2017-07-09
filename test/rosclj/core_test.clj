(ns rosclj.core-test
  (:use midje.sweet)
  (:require [taoensso.timbre :as log]
            [manifold.deferred :as d]
            [cats.monad.maybe :as maybe]
            [rosclj.core :refer :all]
            [rosclj.tcpros :as tcp]))

(def port 7778)

(log/set-level! :debug)

(fact "header parse works"
      (tcp/parse-tcpros-header {:header ["a=b" "c=d"]}) => {"a" "b"
                                                            "c" "d"})

(fact "creating a super simple looping handler for tcp server"
      (let [h (fn [so info]
                (d/let-flow [msg (tcp/read! so)]
                  (when-not (= :empty msg)
                    (log/warn "received: " (str msg)))))
            srv (tcp/make-ros-server h port)
            cli @(tcp/make-ros-client "localhost" port)]
        (try
          (tcp/write! cli [:header ["a=b" "c=d"] :message "blah"]) => cli
          (finally (Thread/sleep 500) (.close srv)))))

(fact "creating a super simple looping handler for tcp server"
      (let [h (fn [so info]
                (d/loop []
                  (log/warn "handler loop")
                  (->
                   (d/let-flow [msg (tcp/read! so)]

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
          (tcp/write! cli [:header ["a=b" "c=d"] :message "oogabooga"]) => cli
          (tcp/write! cli [:header ["e=f" "g=h"] :message "blah"]) => cli
          (finally (Thread/sleep 1000) (.close serv)))))

(defn send-recv! [cli v]
  (-> cli
      (tcp/write! v)
      tcp/read!))

(fact "server connection handler passes to the correct sub-handler"
      (let [srv (tcp/make-ros-server (partial tcp/server-connection-handler {}) port)
            cli1 @(tcp/make-ros-client "localhost" port)
            cli2 @(tcp/make-ros-client "localhost" port)
            cli3 @(tcp/make-ros-client "localhost" port)]
        (try
          (maybe/from-maybe
           (tcp/from-tcpros-msg-as-string
            @(send-recv! cli1 [:header ["topic=std_msgs/String"] :message "blah"]))) => (contains {:message "ok"})
          (maybe/from-maybe
           (tcp/from-tcpros-msg-as-string
            @(send-recv! cli2 [:header ["service=std_msgs/Empty"] :message "oogabooga"]))) => (contains {:message "ok"})
          (maybe/from-maybe
           (tcp/from-tcpros-msg-as-string
            @(send-recv! cli3 [:header ["service=std_msgs/Empty" "probe=1"] :message "oogabooga"]))) => nil
          (finally (Thread/sleep 1000) (.close srv)))))
