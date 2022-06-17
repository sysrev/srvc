#!/usr/bin/env bb

(load-file "hash.clj")

(ns sink
  (:require [babashka.curl :as curl]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [insilica.canonical-json :as json]))

(defn api-route [remote & path-parts]
  (str remote (when-not (str/ends-with? remote "/") "/")
       "api/v1/" (str/join "/" path-parts)))

(defn existing-hashes [remote]
  (->> (curl/get (api-route remote "hashes") {:as :stream})
       :body io/reader line-seq
       (map json/read-str)
       (into #{})))

(defn write-item [item writer existing]
  (when-not (existing (:hash item))
    (json/write item writer)
    (.write writer "\n")
    (.flush writer)))

(let [[config-file infile] *command-line-args*
      {:keys [db labels]} (json/read-str (slurp config-file) :key-fn keyword)]
  (with-open [os (java.io.PipedOutputStream.)]
    (let [writer (io/writer os)
          existing (atom (existing-hashes db))
          upload (future
                   (curl/post (api-route db "upload")
                              {:body (java.io.PipedInputStream. os)}))]
      (doseq [label labels]
        (let [label (hash/add-hash {:data label :type "label"})]
          (write-item label writer @existing)
          (swap! existing conj (:hash label))))
      (doseq [line (-> infile io/reader line-seq)
              :let [{:keys [hash] :as m} (json/read-str line :key-fn keyword)
                    actual-hash (hash/hash m)]]
        (if (not= actual-hash hash)
          (throw (ex-info "Hash mismatch" {:actual-hash actual-hash :expected-hash hash :item m}))
          (when-not (@existing hash)
            (.write writer line)
            (.write writer "\n")
            (.flush writer)
            (swap! existing conj hash))))
      (.close os)
      @upload)))

nil
