(ns rosclj.msg
  (:require [rosclj.serialization :as s]
            [instaparse.core :as insta])
  (:import (java.nio ByteBuffer)))

(def ^:dynamic *ros-package-path* (atom []))
(def ^:dynamic *ros-message-specs* (atom {}))

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

;; Generate a message specification from a parsed message file


;; serialize arbitrary messages
(defmethod s/serialize IMessage [_ ^ByteBuffer stream ^IMessage obj]
  (to-stream obj stream))
(defmethod s/deserialize IMessage [_ ^ByteBuffer stream ^IMessage obj]
  (from-stream obj stream))