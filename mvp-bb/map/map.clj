#!/usr/bin/env bb

(require '[cheshire.core :as json]
         '[clojure.java.io :as io])

(doseq [{:keys [i]} (-> *in*
                       io/reader
                      (json/parsed-seq true))]
  (-> {:i i :j (inc i)}
      json/generate-string
      println))
