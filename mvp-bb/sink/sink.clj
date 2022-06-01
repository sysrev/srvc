#!/usr/bin/env bb

(require '[babashka.pods :as pods]
         '[cheshire.core :as json]
         '[clojure.java.io :as io])

(pods/load-pod 'org.babashka/go-sqlite3 "0.1.0")
(require '[pod.babashka.go-sqlite3 :as sqlite])

(defn create-schema [db]
  (sqlite/execute! db ["create table if not exists data (json TEXT)"]))

(let [[infile db] *command-line-args*]
  (create-schema db)
  (doseq [m (-> infile
                io/reader
                (json/parsed-seq true))]
    (->> m
         json/generate-string
         (vector "insert into data (json) values (?)")
         (sqlite/execute! db))))
