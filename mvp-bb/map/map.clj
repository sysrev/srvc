#!/usr/bin/env bb

(require '[babashka.deps :as deps]
         '[clojure.string :as str])

(deps/add-deps '{:deps {co.insilica/bb-srvc {:mvn/version "0.1.0"}}})

(require '[insilica.canonical-json :as json]
         '[srvc.bb :as sb])

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
        (and (not required) (str/starts-with? "skip" response)) nil
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

(defmethod read-answer "string"
  [{:keys [categories question required]}]
  (println question)
  (loop []
    (print "? ")
    (flush)
    (let [response (str/trim (read-line))]
      (if (and required (str/blank? response))
        (recur)
        response))))

(defn read-answers [labels]
  (when (seq labels)
    (loop [answers {}
           [{:keys [data hash]}
            & more] labels]
      (let [{:keys [inclusion_values]} data
            answer (read-answer data)
            answers (assoc answers hash answer)]
        (println)
        (if (or (empty? more)
                (and (seq inclusion_values)
                     (not (some (partial = answer) inclusion_values))))
          answers
          (recur answers more))))))

(sb/map
 (fn [{:keys [current_labels reviewer]}
      {:keys [data hash type uri] :as event}]
   (if-not (= "document" type)
     [event]
     (do
       (when data
         (json/write data *out*)
         (println))
       (some-> uri println)
       (->> (read-answers current_labels)
            (map (fn [[label-hash answer]]
                   {:data {:answer answer
                           :document hash
                           :label label-hash
                           :reviewer reviewer
                           :timestamp (sb/unix-time)}
                    :type "label-answer"}))
            (cons event))))))
