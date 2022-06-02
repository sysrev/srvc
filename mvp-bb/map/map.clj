#!/usr/bin/env bb

(require '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn write-json [writer m]
  (->> m
       json/generate-string
       (.write writer))
  (.write writer "\n")
  (.flush writer))

(let [[infile outfile] *command-line-args*]
  (with-open [writer (io/writer outfile)]
    (doseq [m (-> infile
                  io/reader
                  (json/parsed-seq true))]
      (prn m)
      (print "Include? [Y/n]  ")
      (flush)
      (loop []
        (let [input (str/lower-case (read-line))]
          (case input
            "n" nil
            "y" (write-json writer m)
            (recur)))))))
