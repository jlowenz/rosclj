(ns rosclj.pkg
  (:require [taoensso.timbre :as log]
            [me.raynes.fs :as fs]
            [rosclj.util :as u]
            [clojure.string :as str]))

(def ^:dynamic *ros-package-path* (atom []))
(def ^:dynamic *ros-packages* (atom {}))

(defn parse-pkg-path
  [p]
  (let [f (clojure.java.io/as-file p)]
    [(.getName f) f]))

;; TODO: should this check for an existing key first?
(defn extend-pkg-map
  [m [pkg-name f]]
  (assoc m pkg-name f))

(defn profile
  "Scan the directories in the `*ros-package-path*` for package.xml files"
  []
  (if (empty? @*ros-package-path*)
    (reset! *ros-package-path*
            (str/split (u/get-env "ROS_PACKAGE_PATH") #":")))
  (let [rpp @*ros-package-path*
        pkg-files (mapv #(fs/find-files % #"package.xml$") rpp)
        pkgs (mapv #(.getParent %) (some not-empty pkg-files))]
    (reset! *ros-packages*
            (reduce extend-pkg-map {} (mapv parse-pkg-path (sort pkgs))))))

(defn find-package
  "Find the package path for the given name. Will profile the ROS package
  path if it hasn't already been done."
  [pkg-name]
  (when (empty? @*ros-packages*) (profile))
  (get @*ros-packages* pkg-name))

(defn find-message-spec [name]
  (let [rpp @*ros-package-path*]
    (loop [p (first rpp)
           ps (next rpp)]
      (if-not p
        nil
        (let [msgs (fs/find-files
                     p (re-pattern (str name ".(:?msg|srv)$")))]
          (if (empty? msgs)
            (recur (first ps) (next ps))
            msgs))))))

(defn parse-msg-path [p]
  (let [f (fs/file p)
        nom (.getName f)
        i (.indexOf nom ".")]
    [(.substring nom 0 i) f]))

(defn extend-msg-map [m [msg-name f]] (assoc m msg-name f))

(defn find-message-files-for-pkg [pkg]
  (let [pkg-file (find-package pkg)
        msg-path (u/make-path (str pkg-file) "msg")
        srv-path (u/make-path (str pkg-file) "srv")
        msgs (fs/find-files msg-path #".*\.msg$")
        srvs (fs/find-files srv-path #".*\.srv$")
        msgs (reduce extend-msg-map {} (mapv parse-msg-path msgs))
        srvs (reduce extend-msg-map {} (mapv parse-msg-path srvs))]
    {:msg msgs
     :srv srvs}))