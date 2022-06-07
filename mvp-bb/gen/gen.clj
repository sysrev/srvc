#!/usr/bin/env bb

(require '[clojure.java.io :as io])

(let [[_config-file outfile] *command-line-args*]
  (with-open [writer (io/writer outfile)]
    (doseq [i (range 1 11)]
      (.write writer (str "{\"data\":{\"i\":" i "}}\n")))))
