#!/usr/bin/env bb

(load-file "hash.clj")

(ns map
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [insilica.canonical-json :as json]))

(defn unix-time []
  (quot (System/currentTimeMillis) 1000))

(defn write-json [m writer]
  (json/write m writer)
  (.write writer "\n")
  (.flush writer))

(defmulti read-answer :type)

(defmethod read-answer "boolean"
  [{:keys [question required]}]
  (print question)
  (print " ")
  (loop []
    (if required
      (print "[Yes/No] ")
      (print "[Yes/No/Skip] "))
    (flush)
    (let [response (-> (read-line) str/trim str/lower-case)]
      (cond
        (empty? response) (recur)
        (str/starts-with? "yes" response) true
        (str/starts-with? "no" response) false
        (and required (str/starts-with? "skip" response)) nil
        :else (recur)))))

(defmethod read-answer "categorical"
  [{:keys [categories question required]}]
  (println question)
  (let [categories (if required
                     categories
                     (into ["Skip Question"] categories))]
    (doseq [[i cat] (map-indexed vector categories)]
      (println (str (inc i) ". " cat)))
    (loop []
      (print "? ")
      (flush)
      (let [response (-> (read-line) str/trim parse-long)]
        (cond
          (not response) (recur)
          (and (not required) (= 1 response)) nil
          (<= 1 response (count categories)) (nth categories (dec response))
          :else (recur))))))

(defn read-answers [labels]
  (when (seq labels)
    (loop [answers {}
           [{:keys [id inclusion-values] :as label} & more] labels]
      (let [answer (read-answer label)
            answers (assoc answers id answer)]
        (println)
        (if (or (empty? more)
                (and (seq inclusion-values)
                     (not (some (partial = answer) inclusion-values))))
          answers
          (recur answers more))))))

(let [[config-file outfile infile] *command-line-args*
      {:keys [current_step labels reviewer]} (json/read-str (slurp config-file) :key-fn keyword)
      labels-map (->> (map (juxt :id identity) labels)
                      (into {}))
      step-labels (->> current_step :labels distinct
                       (map labels-map))]
  (with-open [writer (io/writer outfile)]
    (doseq [line (-> infile io/reader line-seq)
            :let [{:keys [data hash type uri] :as m} (json/read-str line :key-fn keyword)]]
      (.write writer line)
      (.write writer "\n")
      (.flush writer)
      (when (= "document" type)
        (when data
          (json/write data *out*)
          (println))
        (some-> uri println)
        (doseq [[k v] (read-answers step-labels)]
          (-> {:data {:answer v
                      :document hash
                      :label (hash/hash {:data (get labels-map k) :type "label"})
                      :reviewer reviewer
                      :timestamp (unix-time)}
               :type "label-answer"}
              hash/add-hash
              (write-json writer)))))))
