#!/usr/bin/env bb

(require '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn write-json [writer m]
  (->> m
       json/generate-string
       (.write writer))
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
  (loop [answers {}
         [{:keys [id inclusion-values] :as label} & more] labels]
    (let [answer (read-answer label)
          answers (assoc answers id answer)]
      (println)
      (if (or (empty? more)
              (and (seq inclusion-values)
                   (not (some (partial = answer) inclusion-values))))
        answers
        (recur answers more)))))

(let [[config-file outfile infile] *command-line-args*
      {:keys [current_step labels]} (json/parse-string (slurp config-file) true)
      labels-map (->> (map (juxt :id identity) labels)
                      (into {}))
      step-labels (->> (into ["sr_include"] (:labels current_step))
                       distinct
                       (map labels-map))]
  (with-open [writer (io/writer outfile)]
    (doseq [m (-> infile
                  io/reader
                  (json/parsed-seq true))]
      (prn (:data m))
      (->> step-labels
           read-answers
           (update m :label-answers merge)
           (write-json writer)))))
