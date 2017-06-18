(ns rosclj.master
  "Talk to the ROS master over XML-RPC.."
  (:require [dire.core :refer [with-handler!]]
            [slingshot.slingshot :refer [throw+]]
            [necessary-evil.core :as xmlrpc]))

(declare ros-rpc-call)

(defn lookup-service
  "Lookup the information for the specified service"
  [node name]
  (ros-rpc-call (:master-uri node) "lookupService" (str name)))

(defn lookup-node
  [node name]
  (ros-rpc-call (:master-uri node) "lookupNode" (str name)))

(defn ros-rpc-call
  "Pushes the ros node name on the arg list and does the call. Throws
  an error if the code <= 0 is returned. Otherwise returns the
  values. Requires that the URI is not nil."
  [node uri name & args]
  (assert uri)
  ;; make the xml-rpc call  
  (let [[code status vals] (xmlrpc/call* uri name (.name node) args)]
    (if (<= code 0)
      (throw+ {:type :xml-rpc-error :code code :status status :vals vals})
      vals)))

;; TODO: a protected call macro
