(ns rosclj.tcpros
  (:require [taoensso.timbre :as log]
            [gloss.core :as gloss]
            [gloss.io :as gio]
            [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [cats.core :as m]
            [cats.monad.maybe :as maybe]
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
    (s/connect (s/map #(gio/encode protocol %) out) s)
    ;; splice the output of the decoded socket stream onto the out stream
    (s/splice out (gio/decode-stream s protocol))
    ;;(s/splice out s) 
    ))

(defn read! [so] (s/take! so :empty))
(defn write! [so v] (s/put! so v) so)
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

;; when reading from the socket stream, it seems to be easiest to
;; access the message within a d/let-flow that abstracts the deferred
;; nature of the stream...

(defn to-tcpros-msg
  [msg]
  (let [hdr (reduce-kv #(conj %1 (str %2 "=" %3)) [] (:header msg))]
    [:header hdr :message (:message msg)]))

(defn from-tcpros-msg
  [msg]
  (if (= :empty msg) (maybe/nothing)
      (let [hmsg (apply hash-map msg)
            hdr (parse-tcpros-header hmsg)]
        (maybe/just {:header hdr :message (:message hmsg)}))))

(defn from-tcpros-msg-as-string
  [msg]
  (m/mlet [hmsg (from-tcpros-msg msg)
           :let [buf (first (:message hmsg))
                 ba (byte-array (.limit buf)) ;; .array DOESN'T work since the ByteBuffers may be VIEWS of a larger buffer
                 _ (.get buf ba)]]
          (m/return (assoc hmsg :message (String. ba "UTF-8")))))

(defn handle-service-connection
  [node msg so info]
  (log/debug "handle-service-connection called")
  [:header [] :message "ok"])

(defn handle-topic-connection
  "Handle topic connection by checking md5 sum, sending back a
  response header, then adding this socket to the publication list for
  this topic. If the connection comes from this caller no response
  needs to be sent."
  [node msg so info]
  (log/debug "handle-topic-connection called")
  ;; get the topic, md5sum and callerid from the header
  (let [hdr (get msg :header)
        topic (get hdr "topic")
        md5 (get hdr "md5sum")
        uri (get hdr "callerid")
        pub (get (get node :publications) topic :unknown-topic)]
    ;; 
    )
  [:header [] :message "ok"])

(defn server-connection-handler
  "Use the TCPROS conventions for determining whether the incoming
  connection is for a topic (header contains `topic` field) or a
  service (header contains `service` field). Hands off to a
  handle-topic or handle-service function."
  [node so info]
  (d/let-flow [msg (read! so)]
    (when-not (= :empty msg)
      (log/warn "server-connection-handler: " (str msg))
      (try 
        (m/mlet [msg' (from-tcpros-msg msg)
                 :let [hdr (:header msg')]]
                (write! so (condp #(contains? %2 %1) hdr
                             "probe" (if (= 1 (Integer/parseInt (get hdr "probe")))
                                       (do (log/warn "Unexpectedly received a tcpros connection with probe set to 1. Closing")
                                           (close! so)))
                             "service" (handle-service-connection node msg' so info)
                             "topic" (handle-topic-connection node msg' so info)
                             (do (log/warn "Got a malformed header:" hdr) (close! so)))))
        (catch Throwable e
          (log/error (str e))
          (close! so))))))
