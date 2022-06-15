#!/usr/bin/env bb

(load-file "hash.clj")

(ns sr
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [insilica.canonical-json :as json]))

(def default-opts
  {:inherit true
   :shutdown p/destroy-tree})

(defn process [args & [opts]]
  (p/process args (merge default-opts opts)))

(defn make-fifo [path]
  @(p/process ["mkfifo" (str path)])
  path)

(def default-include
  {:id "sr_include"
   :type "boolean"
   :inclusion-values [true]
   :question "Include?"
   :required true})

(defn canonical-label [label]
  (-> label
      (update :id str/lower-case)
      (update :required boolean)
      (update :type str/lower-case)))

(defn parse-labels [labels]
  (let [labels (mapv canonical-label labels)]
    (if (some #(= "sr_include" (:id %)) labels)
      labels
      (into [default-include] labels))))

(defn get-config [filename]
  (-> filename slurp yaml/parse-string
      (update :labels parse-labels)))

(defn usage []
  (println "Usage: sr review flow-id"))

(defn review [flow-name]
  (fs/with-temp-dir [dir {:prefix "srvc"}]
    (let [config (get-config "sr.yaml")
          {:keys [steps]} (get-in config [:flows (keyword flow-name)])]
      (loop [[{:keys [run] :as step} & more] steps
             in-file nil]
        (let [config-json (str (fs/path dir (str (random-uuid) ".json")))]
          (-> config
              (assoc :current_step step)
              json/write-str
              (->> (spit config-json)))
          (if more
            (let [out-file (-> (fs/path dir (str (random-uuid) ".fifo")) make-fifo str)]
              (process ["bb" run config-json out-file in-file])
              (recur more out-file))
            (do @(process ["bb" run config-json in-file])
                nil)))))))

(let [[command & args] *command-line-args*
      command (some-> command str/lower-case)]
  (case command
    nil (usage)
    "review" (apply review args)
    (do (println "Unknown command" (pr-str command))
        (System/exit 1))))
