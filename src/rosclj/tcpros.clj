(ns rosclj.tcpros
  (:require [taoensso.timbre :as log]
            [gloss.core :as gloss]
            [gloss.io :as gio]
            [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure.string :as str]))

;; how many second to wait until giving up
(def ^:dynamic *tcp-timeout* 5.0)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Gloss protocols for TCPROS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;(gloss/defcodec key-value [(gloss/string :ascii) "=" (gloss/string :ascii)])
;(gloss/defcodec key-value (gloss/repeated (gloss/string :ascii :delimiters [\=]) :prefix :none))
(gloss/defcodec ros-field (gloss/finite-frame :uint32-le (gloss/string :ascii)))
(gloss/defcodec ros-header (gloss/finite-frame :uint32-le (gloss/repeated ros-field :prefix :none)))
(gloss/defcodec ros-message-content (gloss/finite-block :uint32-le))
(gloss/defcodec ros-message [:header ros-header :message ros-message-content])

(gloss/defcodec simple {:msg (gloss/finite-frame :uint32-le (gloss/string :ascii))})

(defn wrap-duplex-stream
  "Wraps the given duplex stream s (socket) for encoding and decoding the given protocol"
  [protocol s]
  (let [out (s/stream)]
    ;; connect the out stream (after mapping the protocol) to the socket s
    (s/connect (s/map #(gio/contiguous (gio/encode protocol %)) out) s)
    ;; splice the output of the decoded socket stream onto the out stream
    (s/splice out (gio/decode-stream s protocol))
    ;;(s/splice out s) 
    ))

(defn read! [so] (s/take! so :empty))
(defn write! [so v] (s/put! so v))
(defn close! [so] (s/close! so))

(defn make-ros-client
  "Create a bidirection tcp client for handling ROS messages"
  [host port]
  ;; asynchronously construct a TCP client  
  (d/chain (tcp/client {:host host :port port})
           #(wrap-duplex-stream ros-message %)))

(defn make-ros-server
  "Create a server with the given handler..."
  [handler port]
  (tcp/start-server
   (fn [s info]
     (log/warn "Handler called..." (str info))
     (handler (wrap-duplex-stream ros-message s) info))
   {:port port}))

(defn ros-node-tcp-server
  "Return a TCP server"
  [port]
  nil)

(defn parse-tcpros-header
  [message]
  (let [hdr (:header message)]
    (reduce #(apply assoc %1 (str/split %2 #"=")) {} hdr)))

(defn server-connection-handler
  "Use the TCPROS conventions for determining whether the incoming
  connection is for a topic (header contains `topic` field) or a
  service (header contains `service` field). Hands off to a
  handle-topic or handle-service function."
  [node s info]
  ;; 
  )
