(ns rosclj.msg
  (:require [rosclj.serialization :as s]
            [rosclj.pkg]
            [instaparse.core :as insta]
            [me.raynes.fs :as fs]
            [taoensso.timbre :as log])
  (:import (java.nio ByteBuffer)))


(defrecord MessageID [pkg name])
(defn make-msg-id
  ([pkg name] (->MessageID pkg name))
  ([_ pkg name] (->MessageID pkg name)))

;; Map MessageIDs to MessageSpecs
(def ^:dynamic *ros-message-specs* (atom {}))
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

;; Describe services with a programmatic specification
;; TODO: service specification

(defrecord ConstantSpec [type name value])

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

(def var-specs
  #{PrimitiveFieldSpec
    StringFieldSpec
    MessageFieldSpec
    PrimitiveArrayFieldSpec
    StringArrayFieldSpec
    MessageArrayFieldSpec})

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

(defn var-spec? [s]
  (contains? var-specs (type s)))
(defn constant-spec? [s]
  (instance? ConstantSpec (type s)))

(defn parse-const [pkg [[ftype] ident val-str]]
  (let [val (read-string val-str)]
    (assert (primitive-type? ftype))
    (->ConstantSpec ftype ident val)))

(declare generate-msg-spec)

(defn load-pkgs-msgs [pkg]
  (let [pkg-files (rosclj.pkg/find-message-files-for-pkg pkg)
        msg-specs (mapv #(generate-msg-spec (second %) pkg (first %))
                        (:msgs pkg-files))
        srv-specs (mapv #(generate-msg-spec (second %) pkg (first %))
                        (:srvs pkg-files))
        msg-map (reduce #(assoc %1 (first %2) (second %2)) {} msg-specs)
        srv-map (reduce #(assoc %1 (first %2) (second %2)) {} srv-specs)]
    (swap! *ros-message-specs* merge msg-map)
    (swap! *ros-message-specs* merge srv-map)))

(defn get-msg-spec [parent-pkg msg-name]
  (let [pkg (if (> (count msg-name) 1) (first msg-name) parent-pkg)
        name (second msg-name)
        mid (->MessageID pkg name)]
    (or (get @*ros-message-specs* mid)
        (do (load-pkgs-msgs pkg)
            (get @*ros-message-specs* mid)))))

(defn get-spec-size [s]
  (cond
    (instance? PrimitiveFieldSpec s)
      (:ser-length s)
    (instance? PrimitiveArrayFieldSpec s)
      (let [sz (:ser-length s)]
        (assert (> sz 0) "Primitive array must be fixed size to pre-compute
        serialized length.")
        sz)
    (instance? MessageFieldSpec s)
      (let [sz (:ser-length s)]
        (assert (> sz 0) (str "Message field " (:name s) " must be fixed size
         to pre-compute serialized length."))
        sz)
    (instance? MessageArrayFieldSpec s)
      (let [sz (:ser-length s)]
        (assert (> sz 0) (str "Message array " (:name s) " must be fixed
        size array and fixed size message to pre-compute serialized length."))
        sz)
    :else (assert false "Fixed size objects required to pre-compute
    serialized length.")))

(defn compute-fixed-length [spec]
  (assert (instance? MessageSpec spec) "operates on MessageSpecs")
  ;; iterate over the fields, sum the length
  (reduce + (map get-spec-size (:fields spec))))

(defn parse-var [pkg [type-spec ident]]
  (let [ftype (first type-spec)]
    (cond
      (primitive-type? ftype)
        (->PrimitiveFieldSpec ftype ident (s/field-type-size ftype))
      (string-type? ftype)
        (->StringFieldSpec ident)
      (message-type? ftype)
        (let [sub-msg-spec (get-msg-spec pkg (next type-spec))
              msg-id (apply make-msg-id pkg (next type-spec))
              ser-length (if (s/is-fixed-size? sub-msg-spec)
                           (compute-fixed-length sub-msg-spec)
                           0)]
          (->MessageFieldSpec msg-id sub-msg-spec
                              ident ser-length)))))

(defn parse-array [pkg [type-spec aspec ident]]
  (let [ftype (first type-spec)
        type-size (or (ftype s/field-type-size) 0)
        arr-size (or (second aspec) 0)]
    (cond
      (primitive-type? ftype)
        (->PrimitiveArrayFieldSpec ftype ident (* arr-size type-size))
      (string-type? ftype)
        (->StringArrayFieldSpec ident arr-size)
      (message-type? ftype)
        (let [sub-msg-spec (get-msg-spec pkg (next type-spec))
              msg-id (apply make-msg-id pkg (next type-spec))
              ser-length (if (s/is-fixed-size? sub-msg-spec)
                           (compute-fixed-length sub-msg-spec)
                           0)]
          (->MessageArrayFieldSpec msg-id sub-msg-spec ident
                                   arr-size (* arr-size ser-length))))))

(defn parse-field [curr-pkg [_ f]]
  (let [kind (first f)]
    (case kind
      :CONSTANT (parse-const curr-pkg (next f))
      :VAR (parse-var curr-pkg (next f))
      :ARRAYVAR (parse-array curr-pkg (next f)))))

;; Generate a message specification from a message filename
(defn generate-msg-spec
  "Generate a message specification from a parsed message"
  [path pkg name]
  (let [msg (parse-message path)
        md5 (compute-ros-md5 path)
        field-defs (filter field-def? msg)
        all-fields (mapv (partial parse-field pkg) field-defs)
        fields (filter var-spec? all-fields)
        constants (filter constant-spec? all-fields)
        spec (->MessageSpec md5 path pkg name constants fields)]
    [(->MessageID pkg name) spec]))

;; serialize arbitrary messages
(defmethod s/serialize :message [_ ^ByteBuffer stream obj]
  (to-stream obj stream))
(defmethod s/deserialize :message [_ ^ByteBuffer stream obj]
  (from-stream obj stream))
