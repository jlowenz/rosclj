(ns rosclj.msg
  (:require [rosclj.serialization :as s]
            [instaparse.core :as insta]
            [me.raynes.fs :as fs])
  (:import (java.nio ByteBuffer)))


(def message-types ["msg" "srv" "action"])


(def msg-base (slurp (clojure.java.io/resource "msg_base.bnf")))
(def msg-head (slurp (clojure.java.io/resource "msg.bnf")))
(def srv-head (slurp (clojure.java.io/resource "srv.bnf")))

;; construct a message parser from a .msg file
(def ros-message-parser
  (insta/parser (str msg-head "\n" msg-base)))
(def ros-service-parser
  (insta/parser (str srv-head "\n" msg-base)))

(defn parse-message [filename]
  (ros-message-parser (slurp (clojure.java.io/file filename))))

(defn parse-service [filename]
  (ros-service-parser (slurp (clojure.java.io/file filename))))

(defprotocol IMessage
  (is-fixed-size? [this]
    "Return whether this message has a fixed serialized size")
  (byte-count [this]
    "Return the number of bytes required for this message.")
  (to-stream [this stream])
  (from-stream [this stream]))

;; Describe messages with a programmatic specification
(defrecord MessageSpec [md5 path pkg name constants fields]
  s/ISerializedLength
  (is-fixed-size? [_]
    (every? s/is-fixed-size? fields))
  (serialized-length [_ obj]
    (reduce + (mapv #(s/serialized-length % obj) fields))))

(defrecord Constant [type name value])

;; FieldSpec types:
;;  PrimitiveFieldSpec
;;  StringFieldSpec
;;  MessageFieldSpec
;;  PrimitiveArrayFieldSpec
;;  StringArrayFieldSpec
;;  MessageArrayFieldSpec

(defn getf [obj name]
  ((keyword name) obj))

(defn getspec [obj name]
  (:_spec (getf obj name)))

(defrecord PrimitiveFieldSpec [type name ser-length]
  s/ISerializedLength
  (is-fixed-size? [_] true)
  (serialized-length [_ _] ser-length))

(defrecord StringFieldSpec [name]
  s/ISerializedLength
  (is-fixed-size? [_] false)
  (serialized-length [_ obj] (+ 4 (count (getf obj name)))))

(defrecord MessageFieldSpec [type type-spec name ser-length]
  s/ISerializedLength
  (is-fixed-size? [_] (is-fixed-size? type-spec))
  (serialized-length [_ obj]
    (if (> ser-length 0)
      ser-length
      (s/serialized-length (getspec obj name) (getf obj name)))))

(defrecord PrimitiveArrayFieldSpec [type name ser-length]
  s/ISerializedLength
  (is-fixed-size? [_] (> ser-length 0))
  (serialized-length [_ obj]
    (if (> ser-length 0)
      ser-length                                            ;; fixed size
      (+ 4 (* (count (getf obj name)) (type s/field-type-size)))))) ;; var size

(defrecord StringArrayFieldSpec [name size]
  s/ISerializedLength
  (is-fixed-size? [_] false)
  (serialized-length [_ obj]
    (let [total-string-size (reduce + #(s/serialized-length % nil)
                                    (getf obj name))]
      (if (> size 0)
        total-string-size
        (+ 4 total-string-size)))))

(defrecord MessageArrayFieldSpec [type type-spec name size ser-length]
  s/ISerializedLength
  (is-fixed-size? [_] (> ser-length 0))
  (serialized-length [_ obj]
    (if (> ser-length 0)
      ser-length
      (let [arr (getf obj name)]
        (if (is-fixed-size? type-spec)
          (+ 4 (* (s/serialized-length type-spec (first arr)) (count arr)))
          (+ 4 (reduce + #(s/serialized-length type-spec %) arr)))))))

;; From (ROS Wiki)[http://wiki.ros.org/ROS/Technical%20Overview#Message_serialization_and_msg_MD5_sums]
;; Message types (msgs) in ROS are versioned using a special MD5 sum
;; calculation of the msg text. In general, client libraries do not implement
;; this MD5 sum calculation directly, instead storing this MD5 sum in
;; auto-generated message source code using the output of
;; roslib/scripts/gendeps. For reference, this MD5 sum is calculated  from
;; the MD5 text of the .msg file, where the MD5 text is the .msg text with:

; comments removed
; whitespace removed
; package names of dependencies removed
; constants reordered ahead of other declarations

; In order to catch changes that occur in embedded message types, the MD5
; text is concatenated with the MD5 text of each of the embedded types, in
; the order that they appear.

(defn get-pkg-name
  "Extract the message package and the message name from the given file path."
  [path mtype]
  (let [file (clojure.java.io/as-file path)
        name (.getName file)
        kind (.getParent file)]
    (assert (= mtype (.getName kind)))
    (let [pkg (.getParent kind)]
      {:pkg (.getName pkg)
       :name name})))

(defn compute-ros-md5
  [path]
  (let [res (clojure.java.shell/sh "rosrun" "roslib" "gendeps" "--md5" path)]
    (.trim (:out res))))

(defn field-def? [el]
  (and (vector? el) (= :FIELD-DEF (first el))))

(defn primitive-type? [ftype]
  (contains? s/primitive-field-types ftype))

(defn string-type? [ftype]
  (= :string ftype))

(defn message-type? [ftype]
  (= :message ftype))

;; [:TYPE [:string]]
;; [:TYPE [:uint32]]
;; [:TYPE [:message "Header"]

(defn parse-const [pkg [[ftype] ident val-str]]
  (let [val (read-string val-str)]
    (assert (primitive-type? ftype))
    (->Constant ftype ident val)))

(defn parse-var [pkg [type-spec ident]]
  (let [ftype (first type-spec)]
    (cond
      (primitive-type? ftype)
        (->PrimitiveFieldSpec ftype ident (s/field-type-size ftype))
      (string-type? ftype)
        (->StringFieldSpec ident)
      (message-type? ftype)
        (->MessageFieldSpec ))))

(defn parse-array [pkg [ftype aspec ident]]
  (let []))

(defn parse-field [curr-pkg f]
  (let [kind (nfirst f)]
    (case kind
      :CONSTANT (parse-const curr-pkg (nnext f))
      :VAR (parse-var curr-pkg (nnext f))
      :ARRAYVAR (parse-array curr-pkg (nnext f)))))

;; Generate a message specification from a message filename
(defn generate-msg-spec
  "Generate a message specification from a parsed message"
  [path]
  (let [msg (parse-message path)
        md5 (compute-ros-md5 path)
        {:keys [pkg name]} (get-pkg-name path "msg")
        field-defs (filter field-def? msg)
        fields (map (partial parse-field pkg) field-defs)]
    ))

;; serialize arbitrary messages
(defmethod s/serialize :message [_ ^ByteBuffer stream obj]
  (to-stream obj stream))
(defmethod s/deserialize :message [_ ^ByteBuffer stream obj]
  (from-stream obj stream))