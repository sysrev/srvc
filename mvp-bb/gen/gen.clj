#!/usr/bin/env bb

(require '[clojure.java.io :as io])

(let [outfile (first *command-line-args*)]
  (with-open [writer (io/writer outfile)]
    (doseq [i (range 1 11)]
      (.write writer (str "{\"i\":" i "}\n")))))
