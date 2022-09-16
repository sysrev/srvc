(ns build
  (:require [org.corfield.build :as bb]))

(def lib 'co.insilica/srvc-server)
(def version "0.2.0")
(defn get-version [opts]
  (str version (when (:snapshot opts) "-SNAPSHOT")))

(defn uberjar "Run the CI pipeline of tests (and build the JAR)." [opts]
  (let [{:keys [lib target version] :as opts}
        (assoc opts :lib lib :version (get-version opts))
        target (or target "target")]
    (-> opts
        bb/clean
        (assoc :ns-compile '[srvc.server]
               :src-pom "template/pom.xml"
               :uber-file (format "%s/%s-v%s-standalone.jar" target (name lib) version))
        bb/uber)))
