(ns rosclj.namespace
  (:require [clojure.string :as str]))

(defn concatenate-ros-names
  "Takes two or more strings and returns a single string with the names
  delimited by /'s"
  [& args]
  (str/join "/" args))

(defn compute-global-name
  [ns node-global-name name]
  (case (get name 0)
    \/ name
    \~ (str node-global-name "/" (subs name 1))
    (str ns name)))

(defn fully-qualified-name
  "Do the translation from a client-code-specified name to a fully-qualified
  name. Handles already fully-qualified name, tilde for private namespaces,
  and remapped names"
  [node name]
  (let [global-name (compute-global-name (:namespace node) (:name node) name)
        remapped (:remapped-names node)]
    (if remapped
      (get remapped global-name global-name)
      global-name)))

(defmacro with-fully-qualified-name [node n & body]
  (assert (symbol? n))
  `(let [~n (fully-qualified-name ~node ~n)]
     ~@body))