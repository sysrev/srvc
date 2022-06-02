#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.pods :as pods]
         '[cheshire.core :as json]
         '[clojure.java.io :as io])

(pods/load-pod 'org.babashka/go-sqlite3 "0.1.0")
(require '[pod.babashka.go-sqlite3 :as sqlite])

(defn reviewed? [db {:keys [data uri]}]
  (boolean
   (cond
     (not (fs/exists? db)) false
     (seq uri) (->> ["select 1 from data where uri = ? limit 1" uri]
                    (sqlite/query db)
                    first)
     :else
     (->> ["select 1 from data where json = ? limit 1"
           (json/generate-string data)]
          (sqlite/query db)
          first))))

(defn write-json [writer m]
  (->> m
       json/generate-string
       (.write writer))
  (.write writer "\n")
  (.flush writer))

(let [[db infile outfile] *command-line-args*]
  (with-open [writer (io/writer outfile)]
    (doseq [m (-> infile
                  io/reader
                  (json/parsed-seq true))]
      (when-not (reviewed? db m)
        (write-json writer m)))))
