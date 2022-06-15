#!/usr/bin/env bb

(load-file "hash.clj")

(ns gen
  (:require [clojure.java.io :as io]
            [insilica.canonical-json :as json]))

(let [[_config-file outfile] *command-line-args*]
  (with-open [writer (io/writer outfile)]
    (doseq [i (range 1 11)]
      (-> {:data {:i i}
           :type "document"}
          hash/add-hash
          (json/write writer))
        (.write writer "\n"))))
