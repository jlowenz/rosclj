(ns rosclj.util
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [rosclj.namespace :as n]
            [rosclj.rosout :as roslog])
  (:import (java.net URI)
           (java.nio.file FileSystems)
           (java.io File)))

;; String
(defn string-trim [s sub]
  (.replaceAll (.replaceAll s (str "^" sub) "") (str sub "$") ""))

;; System
(defn get-env [name]
  (System/getenv name))

(defn make-path
  "Construct a path string from a set of components"
  [& comps]
  (let [fs (FileSystems/getDefault)]
    (str (.getPath fs (first comps) (into-array (next comps))))))

(defn ensure-directories-exist
  [path]
  (let [^File f (clojure.java.io/as-file path)]
    (.mkdirs f)))

;; Network Utilities
(defn make-uri
  ([str] (URI. str))
  ([host port] (URI. "http" nil host port nil nil nil)))

;; Logging
(defn get-ros-log-location
  [node]
  (let [log-dir (get-env "ROS_LOG_DIR")
        ros-home-dir (get-env "ROS_HOME")
        home-dir (get-env "HOME")]
    (or roslog/*ros-log-path*
        (make-path
          (or log-dir
              (when ros-home-dir (make-path ros-home-dir "log"))
              (when home-dir (make-path home-dir ".ros" "log"))
              (throw (RuntimeException. "None of possible log directories
              worked. Even the HOME env var was not set.")))
          (format "%s-%s.log"
                  (if (.startsWith (:name node) "/")
                    (.substring (:name node) 1)
                    (:name node))
                  (System/currentTimeMillis))))))

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
  (loop [node node
         params {}
         [lhs rhs] (first remappings)
         xs (next remappings)]
    (if lhs
      (cond
        (= lhs "__ns") (let [ns (postprocess-namespace rhs)
                             nom (compute-node-name ns name)]
                         (recur (assoc node :namespace ns :name nom) params
                                (first xs) (next xs)))
        (= lhs "__name") (recur (assoc node :name (compute-node-name
                                                     namespace rhs))
                                params
                                (first xs) (next xs))
        (= lhs "__log") (recur (assoc node :roslog rhs) params
                               (first xs) (next xs))
        (.startsWith lhs "_") (let [param (str "~" (.substring lhs 1))
                                    val (read-string rhs)
                                    val (if (symbol? val) rhs val)]
                                (recur node (assoc params param val)
                                       (first xs) (next xs)))
        :else (recur
                (assoc-in node
                          [:remapped (n/compute-global-name namespace name lhs)]
                          (n/compute-global-name namespace name rhs))
                     params (first xs) (next xs)))
      [node params])))

(defn parse-command-line-args
  [node args]
  (let [namespace (postprocess-namespace (or (get-env "ROS_NAMESPACE") "/"))
        args (if (string? args) (cli/parse-opts args cli-options) args)
        name (compute-node-name namespace (:name node))
        remappings (mapv parse-remapping args)]
    (process-command-line-remappings remappings node)))