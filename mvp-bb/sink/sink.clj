#!/usr/bin/env bb

(require '[cheshire.core :as json]
         '[clojure.java.io :as io])

(with-open [writer (io/writer "sink.log")]
  (doseq [m (-> *in*
                io/reader
                (json/parsed-seq true))]
    (->> m
         json/generate-string
         (.write writer))
    (.write writer "\n")))
