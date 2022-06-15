#!/usr/bin/env bb

(load-file "hash.clj")

(ns sink
  (:require [clojure.java.io :as io]
            [insilica.canonical-json :as json]))

(defn existing-hashes [file]
  (try
    (with-open [reader (io/reader file)]
      (loop [[item & more] (line-seq reader)
             hashes (transient #{})]
        (if-not item
          (persistent! hashes)
          (recur more (conj! hashes (-> item json/read-str (get "hash")))))))
    (catch java.io.FileNotFoundException _)))

(defn write-item [item writer existing]
  (when-not (existing (:hash item))
    (json/write item writer)
    (.write writer "\n")
    (.flush writer)))

(let [[config-file infile] *command-line-args*
      {:keys [db labels]} (json/read-str (slurp config-file) :key-fn keyword)]
  (with-open [writer (io/writer db :append true)]
    (let [existing (existing-hashes db)]
      (doseq [label labels]
        (let [label (hash/add-hash {:data label :type "label"})]
          (write-item label writer existing)))
      (doseq [line (-> infile io/reader line-seq)
              :let [{:keys [hash] :as m} (json/read-str line :key-fn keyword)
                    actual-hash (hash/hash m)]]
        (if (not= actual-hash hash)
          (throw (ex-info "Hash mismatch" {:actual-hash actual-hash :expected-hash hash :item m}))
          (when-not (existing hash)
            (.write writer line)
            (.write writer "\n")
            (.flush writer)))))))
