#!/usr/bin/env bb

(require '[cheshire.core :as json]
         '[clojure.java.io :as io])

(defn reviewed-docs [file reviewer]
  (try
    (with-open [reader (io/reader file)]
      (loop [[line & more] (line-seq reader)
             hashes (transient #{})]
        (let [{:keys [data type]} (some-> line (json/parse-string true))]
          (cond
            (not line) (persistent! hashes)
            (and (= "label-answer" type) (= reviewer (:reviewer data)))
            #__ (recur more (conj! hashes (:document data)))
            :else (recur more hashes)))))
    (catch java.io.FileNotFoundException _)))

(let [[config-file outfile infile] *command-line-args*
      {:keys [db reviewer]} (json/parse-string (slurp config-file) true)
      reviewed (or (reviewed-docs db reviewer) #{})]
  (with-open [writer (io/writer outfile)]
    (doseq [line (line-seq (io/reader infile))
            :let [{:strs [hash]} (json/parse-string line)]]
      (when-not (reviewed hash)
        (.write writer line)
        (.write writer "\n")
        (.flush writer)))))
