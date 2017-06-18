(defproject rosclj "0.1.0-SNAPSHOT"
  :description "A ROS client library "
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.cli "0.3.5"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [com.taoensso/timbre "4.10.0"] ;; logging
                 [midje "1.8.3"]
                 [me.raynes/fs "1.4.6"]
                 [necessary-evil "2.0.2"] ;; xml-rpc
                 [dire "0.5.4"] ;; error handling
                 [slingshot "0.12.2"] ;; enhanced throw
                 [aleph "0.4.3"] ;; asynchronous networking
                 [gloss "0.2.6"] ;; byte format DSL - compiles encoder/decoders on the fly
                 [manifold "0.1.6"] ;; asynchronous programming primitives for composable systems
                 [byte-streams "0.2.2"]
                 [instaparse "1.4.3"]])
