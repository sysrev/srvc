(ns srvc.server.review
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [hyperlight.http-proxy :as http-proxy])
  (:import [org.apache.commons.io.input Tailer TailerListener]))

(def default-opts
  {:in nil
   :err :inherit
   :out :inherit
   :shutdown p/destroy-tree})

(defn process [args & [opts]]
  (p/process args (merge default-opts opts)))

(defn load-config [filename]
  (yaml/parse-stream (io/reader (fs/file filename))))

(defrecord TailListener [f g]
  TailerListener
  (fileNotFound [_this])
  (fileRotated [_this])
  (^void handle [_this ^String line]
    (f line))
  (^void handle [_this ^Exception e]
    (g e))
  (init [_this _tailer]))

(defn tailer [f g file]
  (Tailer.
   (fs/file file)
   (TailListener. f g)
   500))

(defn start-daemon [runnable]
  (doto (Thread. runnable)
    (.setDaemon true)
    .start))

(defn review-process [config flow-name add-events! tail-exception!]
  (let [db (:db config)
        temp-dir (fs/create-temp-dir)
        config-file (fs/path temp-dir (str "config-" (random-uuid) ".yaml"))
        sink (fs/path temp-dir (str "sink-" (random-uuid) ".jsonl"))
        config (assoc config :db (str sink) :sink_all_events true)]
    (with-open [writer (io/writer (fs/file config-file))]
      (yaml/generate-stream writer config))
    (when (fs/exists? db)
      (fs/copy db sink))
    {:process (process ["sr" "--config" (str config-file) "review" flow-name])
     :sink-path sink
     :sink-thread (-> (tailer add-events! tail-exception! sink) start-daemon)
     :temp-dir temp-dir}))

(defn proxy-handler [forward-port]
  (http-proxy/create-handler
   {:url (str "http://localhost:" forward-port)}))

(defn proxy-server [forward-port listen-port]
  (http-proxy/start-server
   (proxy-handler forward-port)
   {:port listen-port}))
