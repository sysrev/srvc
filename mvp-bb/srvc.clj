#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clj-yaml.core :as yaml]
         '[clojure.string :as str])

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

(fs/with-temp-dir [dir {:prefix "srvc"}]
  (let [config (get-config "sr.yaml")
        config-json (str (fs/path dir "config.json"))
        _ (spit config-json (json/generate-string config))
        gen-fifo (-> (fs/path dir "gen.fifo") make-fifo str)
        gen (process ["bb" "gen/gen.clj" gen-fifo])
        remove-reviewed-fifo (-> (fs/path dir "remove-reviewed.fifo") make-fifo str)
        remove-reviewed (process ["bb" "map/remove-reviewed.clj" "sink.db" gen-fifo remove-reviewed-fifo])
        map-fifo (-> (fs/path dir "map.fifo") make-fifo str)
        map (process ["bb" "map/map.clj" config-json remove-reviewed-fifo map-fifo])
        sink (process ["bb" "sink/sink.clj" config-json "sink.db" map-fifo])]
    @sink))

nil
