#!/usr/bin/env bb

(require '[cheshire.core :as json]
         '[clojure.java.io :as io])

(let [[infile outfile] *command-line-args*]
  (with-open [writer (io/writer outfile)]
    (doseq [m (-> infile
                  io/reader
                  (json/parsed-seq true))]
      (->> m
           json/generate-string
           (.write writer))
      (.write writer "\n"))))
