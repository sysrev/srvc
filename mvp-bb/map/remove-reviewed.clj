#!/usr/bin/env bb

(require '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

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

(defn remote-target? [s]
  (let [lc (str/lower-case s)]
    (or (str/starts-with? lc "http://")
        (str/starts-with? lc "https://"))))

(defn api-route [target & path-parts]
  (str target (when-not (str/ends-with? target "/") "/")
       "api/v1/" (str/join "/" path-parts)))

(defn remote-reviewed? [remote reviewer doc-hash]
  (try
    (->> (curl/get (api-route remote "document" doc-hash "label-answers")
                   {:as :stream})
         :body io/reader line-seq
         (map #(json/parse-string % true))
         (some #(-> % :data :reviewer (= reviewer)))
         boolean)
    (catch clojure.lang.ExceptionInfo e
      (when-not (= 404 (:status (ex-data e)))
        (throw e)))))

(let [config-file (System/getenv "SR_CONFIG")
      infile (System/getenv "SR_INPUT")
      outfile (System/getenv "SR_OUTPUT")
      {:keys [db reviewer]} (json/parse-string (slurp config-file) true)
      reviewed? (if (remote-target? db)
                  (partial remote-reviewed? db reviewer)
                  (comp boolean
                        (or (reviewed-docs db reviewer) #{})))]
  (with-open [writer (io/writer outfile)]
    (doseq [line (line-seq (io/reader infile))
            :let [{:strs [hash]} (json/parse-string line)]]
      (when-not (reviewed? hash)
        (.write writer line)
        (.write writer "\n")
        (.flush writer)))))
