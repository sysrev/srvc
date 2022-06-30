#!/usr/bin/env bb

  (require '[babashka.curl :as curl]
           '[babashka.deps :as deps]
           '[clojure.java.io :as io]
           '[clojure.string :as str])

(deps/add-deps '{:deps {co.insilica/bb-srvc {:mvn/version "0.4.0"}}})

(require '[insilica.canonical-json :as json]
         '[srvc.bb :as sb])

(defn api-route [remote & path-parts]
  (str remote (when-not (str/ends-with? remote "/") "/")
       "api/v1/" (str/join "/" path-parts)))

(defn existing-hashes [remote]
  (->> (curl/get (api-route remote "hashes") {:as :stream})
       :body io/reader line-seq
       (map json/read-str)
       (into #{})))

(defn upload-lines! [remote lines]
  (with-open [os (java.io.PipedOutputStream.)]
    (let [is (java.io.PipedInputStream. os)
          writer (io/writer os)
          upload (future
                   (curl/post (api-route remote "upload")
                              {:body is}))]
      (doseq [line lines]
        (.write writer line)
        (.write writer "\n"))
      (.flush writer)
      (.close os)
      @upload)))

(let [config-file (System/getenv "SR_CONFIG")
      infile (System/getenv "SR_INPUT")
      {:keys [db labels]} (json/read-str (slurp config-file) :key-fn keyword)
      label-events (map #(sb/add-hash {:data % :type "label"})
                       (vals labels))
      existing (atom (existing-hashes db))
      new-labels (remove (comp @existing :hash) label-events)]
  (upload-lines! db (map json/write-str new-labels))
  (swap! existing into (map :hash new-labels))
  (doseq [line (-> infile io/reader line-seq)
          :let [{:keys [hash] :as m} (json/read-str line :key-fn keyword)
                actual-hash (sb/json-hash m)]]
    (if (not= actual-hash hash)
      (throw (ex-info "Hash mismatch" {:actual-hash actual-hash :expected-hash hash :item m}))
      (when-not (@existing hash)
        (upload-lines! db [line])
        (swap! existing conj hash)))))

nil
