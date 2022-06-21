#!/usr/bin/env bb

(ns sr
  (:require [babashka.curl :as curl]
            [babashka.deps :as deps]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(deps/add-deps '{:deps {co.insilica/bb-srvc {:local/root "/home/john/src/bb-srvc"}}})

(require '[insilica.canonical-json :as json]
         '[srvc.bb :as sb])

(def default-opts
  {:inherit true
   :shutdown p/destroy-tree})

(def default-sink-step {:run "sink/sink.clj"})
(def remote-sink-step {:run "sink/sink-remote.clj"})

(defn process [args & [opts]]
  (p/process args (merge default-opts opts)))

(defn make-fifo [path]
  @(p/process ["mkfifo" (str path)])
  path)

;; TODO #4 'type' should be wrapped into json-schema.
(defn canonical-label [id label]
  (-> label
      (assoc :id (str/lower-case (name id)))
      (update :required boolean)
      (update :type str/lower-case))) 

(defn parse-labels [labels]
  (->> labels
       (map (fn [[k v]] [k (canonical-label k v)]))
       (into {})))

(defn get-config [filename]
  (-> filename slurp yaml/parse-string
      (update :labels parse-labels)))

(defn usage []
  (println "Usage: sr review flow-id"))

(defn step-labels [{:keys [labels]} step]
  (->> step :labels
       (map #(sb/add-hash {:data (labels (keyword %)) :type "label"}))))

(defn step-config [config step]
  (cond-> (assoc config :current_step step)
    (seq (:labels step)) (assoc :current_labels (step-labels config step))))

(defn write-step-config [config dir step]
  (let [config-json (str (fs/path dir (str (random-uuid) ".json")))]
    (-> (step-config config step)
        json/write-str
        (->> (spit config-json)))
    config-json))

(defn remote-target? [s]
  (let [lc (str/lower-case s)]
    (or (str/starts-with? lc "http://")
        (str/starts-with? lc "https://"))))

(defn api-route [target & path-parts]
  (str target (when-not (str/ends-with? target "/") "/")
       "api/v1/" (str/join "/" path-parts)))

(defn make-remote-in-file [in-source dir]
  (let [in-file (-> (fs/path dir (str (random-uuid) ".fifo")) make-fifo str)
        server-hashes (->> (curl/get (api-route in-source "hashes") {:as :stream})
                           :body io/reader line-seq
                           (map json/read-str))]
    (future
      (with-open [writer (io/writer in-file)]
        (doseq [hash server-hashes]
          (->> (curl/get (api-route in-source "hash" hash))
               :body
               (.write writer))
          (.write writer "\n")
          (.flush writer))))
    in-file))

(defn push-to-target [in-source out-file]
  (when-not (or (remote-target? out-file) (fs/exists? out-file))
    (-> out-file fs/absolutize fs/parent fs/create-dirs)
    (fs/create-file out-file))
  (fs/with-temp-dir [dir {:prefix "srvc"}]
    (let [config (-> (get-config "sr.yaml")
                     (assoc :db out-file))
          {:keys [run] :as step} (if (remote-target? out-file)
                                   remote-sink-step
                                   default-sink-step)
          config-json (write-step-config config dir step)
          in-file (if (remote-target? in-source)
                    (make-remote-in-file in-source dir)
                    in-source)]
      @(process ["perl" run config-json in-file]))))

(defn pull [target]
  (let [{:keys [db]} (get-config "sr.yaml")]
    (push-to-target target db)))

(defn push [target]
  (let [{:keys [db]} (get-config "sr.yaml")]
    (push-to-target db target)))

(defn sync [target]
  (pull target)
  (push target))

(defn review [flow-name]
  (fs/with-temp-dir [dir {:prefix "srvc"}]
    (let [{:keys [db] :as config} (get-config "sr.yaml")
          {:keys [steps]} (get-in config [:flows (keyword flow-name)])
          steps (concat steps [(if (remote-target? db)
                                 remote-sink-step
                                 default-sink-step)])]
      (loop [[{:keys [run] :as step} & more] steps
             in-file nil]
        (let [config-json (write-step-config config dir step)]
          (if more
            (let [out-file (-> (fs/path dir (str (random-uuid) ".fifo")) make-fifo str)]
              (process ["perl" run config-json out-file in-file])
              (recur more out-file))
            @(process ["perl" run config-json in-file])))))))

(let [[command & args] *command-line-args*
      command (some-> command str/lower-case)]
  (case command
    nil (usage)
    "review" (apply review args)
    "pull" (apply pull args)
    "push" (apply push args)
    "sync" (apply sync args)
    (do (println "Unknown command" (pr-str command))
        (System/exit 1))))

nil
