(ns rosclj.util
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [rosclj.namespace :as n])
  (:import (java.net URI)))

;; String
(defn string-trim [s sub]
  (.replaceAll (.replaceAll s (str "^" sub) "") (str sub "$") ""))

;; System
(defn get-env [name]
  (System/getenv name))

;; Network Utilities
(defn make-uri
  ([str] (URI. str))
  ([host port] (URI. "http" nil host port nil nil nil)))

;; Command-line utilities
(def cli-options [])

(defn parse-remapping
  "If arg is of the form FOO:=BAR then return FOO and BAR otherwise nil"
  [arg]
  (let [i (.indexOf arg ":=")]
    (if (> i 0)
      [(.substring arg 0 i) (.substring arg (+ i 2))])))

(defn postprocess-namespace [ns]
  (let [ns (if (.startsWith ns "/") ns (str "/" ns))
        ns (if (.endsWith ns "/") ns (str ns "/"))]
    ns))

(defn compute-node-name [ns name]
  (str ns (string-trim name "/")))

(defn process-command-line-remappings
  [remappings node]
  ;; maybe this one is going too far
  (loop [namespace (:namespace node)
         name (:name node)
         remapped {}
         params {}
         [lhs rhs] (first remappings)
         xs (next remappings)]
    (if lhs
      (cond
        (= lhs "__ns") (let [ns (postprocess-namespace rhs)
                             nom (compute-node-name ns name)]
                         (recur ns nom remapped (first xs) (next xs)))
        (= lhs "__name") (recur namespace (compute-node-name namespace rhs)
                                (first xs) (next xs))
        (= lhs "__log") (recur namespace name remapped (first xs) (next xs))
        (.startsWith lhs "_") (let [param (str "~" (.substring lhs 1))
                                    val (read-string rhs)]
                                (if (symbol? val)
                                  (recur namespace name remapped [param rhs] xs)
                                  (recur namespace name remapped [param val] xs)))
        :else (recur namespace name (assoc remapped
                                      (n/compute-global-name namespace name
                                                             lhs)
                                      (n/compute-global-name namespace name
                                                             rhs)))
        ))))

(defn parse-command-line-args
  [node args]
  (let [namespace (postprocess-namespace (or (get-env "ROS_NAMESPACE") "/"))
        args (if (string? args) (cli/parse-opts args cli-options) args)
        name (compute-node-name namespace (:name node))
        remappings (mapv parse-remapping args)
        remapped (process-command-line-remappings remappings name namespace)]
    {:name name
     :ns namespace
     :remapped remapped}))