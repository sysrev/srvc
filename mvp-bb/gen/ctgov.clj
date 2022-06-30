#!/usr/bin/env bb

(require '[babashka.deps :as deps]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[org.httpkit.client :as http])

(deps/add-deps '{:deps {co.insilica/bb-srvc {:mvn/version "0.4.0"}}})

(require '[insilica.canonical-json :as json]
         '[srvc.bb :as sb])

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
          (json/read-str :key-fn keyword)
          :FullStudiesResponse))))

(defn nct-id [study]
  (-> study :ProtocolSection :IdentificationModule :NCTId))

(defn study-url [study]
  (str "https://clinicaltrials.gov/ct2/show/" (nct-id study)))

(defn read-search-term []
  (print "ClinicalTrials.gov search:  ")
  (flush)
  (read-line))

(let [config-file (System/getenv "SR_CONFIG")
      outfile (System/getenv "SR_OUTPUT")
      {:keys [current_step]} (json/read-str (slurp config-file) :key-fn keyword)
      search-term (some-> current_step :config :search_term str/trim)
      query (or search-term (read-search-term))]
  (with-open [writer (io/writer outfile)]
    (loop [min-rank 1]
      (let [{:keys [FullStudies MaxRank]} (search query min-rank)]
        (when (seq FullStudies)
          (doseq [{:keys [Study]} FullStudies]
            (-> {:type "document"
                 :uri (study-url Study)}
                 sb/add-hash
                 (json/write writer))
            (.write writer "\n"))
          (recur (inc MaxRank)))))))
