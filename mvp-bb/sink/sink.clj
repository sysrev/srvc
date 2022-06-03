#!/usr/bin/env bb

(require '[babashka.pods :as pods]
         '[cheshire.core :as json]
         '[clojure.java.io :as io])

(pods/load-pod 'org.babashka/go-sqlite3 "0.1.0")
(require '[pod.babashka.go-sqlite3 :as sqlite])

(def schema
  ["create table if not exists sr_data (json TEXT, uri TEXT)"
   "create index if not exists sr_data_uri on sr_data (uri)"])

(defn create-schema [db]
  (doseq [statement schema]
    (sqlite/execute! db statement)))

(let [[infile db] *command-line-args*]
  (create-schema db)
  (doseq [{:keys [data uri]}
          (-> infile
              io/reader
              (json/parsed-seq true))]
    (->> ["insert into sr_data (json, uri) values (?, ?)"
          (json/generate-string data)
          uri]
         (sqlite/execute! db))))
