#!/usr/bin/env bb

(require '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[org.httpkit.client :as http])

(def api-url
  "https://clinicaltrials.gov/api/query/full_studies")

(def page-size 10)

(defn get-results [query min-rank page-size]
  (let [{:keys [error] :as response}
        #__ @(http/get api-url {:query-params
                                {:expr query
                                 :fmt "json"
                                 :max_rnk (+ min-rank page-size)
                                 :min_rnk min-rank}})]
    (cond
      (not error) response
      (or (>= 1 page-size)
          (not= "Corrupt GZIP trailer" (ex-message error)))
      (throw error)
      :else (recur query min-rank (quot page-size 2)))))

(defn search [query min-rank]
  (let [{:keys [body status] :as response}
        #__  (get-results query min-rank page-size)]
    (if-not (= 200 status)
     (throw (ex-info (str "Error: HTTP Status " status)
                     {:response response}))
      (-> body
          (json/parse-string true)
          :FullStudiesResponse))))

(print "ClinicalTrials.gov search:  ")
(flush)

(let [outfile (first *command-line-args*)
      query (read-line)]
  (with-open [writer (io/writer outfile)]
    (loop [min-rank 1]
      (let [{:keys [FullStudies MaxRank]} (search query min-rank)]
        (when (seq FullStudies)
          (doseq [m FullStudies]
            (.write writer (json/generate-string (:Study m)))
            (.write writer "\n"))
          (recur (inc MaxRank)))))))
