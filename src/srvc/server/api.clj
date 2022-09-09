(ns srvc.server.api
  (:refer-clojure :exclude [hash])
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [muuntaja.middleware :as mw]
            [org.httpkit.server :as server]
            [reitit.core :as re]))

(defonce write-lock (Object.))

(defn err [message]
  {:body {:error message}})

(def not-found
  (-> (err "not-found")
      (assoc :status 404)))

(def success
  {:body {:success true}})

(defn get-projects [_request]
  (let [projects-dir (fs/real-path (fs/path "."))
        projects (->> (fs/list-dir projects-dir)
                      (keep #(when (fs/exists? (fs/path % "sr.yaml"))
                               (str (fs/relativize projects-dir %))))
                      (sort-by str/lower-case))]
    {:body {:projects projects}}))

(defn POST-project [{:keys [body-params]}]
  (let [{:keys [name]} body-params
        sr-yaml (fs/path name "sr.yaml")]
    (if (fs/exists? sr-yaml)
      (err "already-exists")
      (do
        (fs/create-dirs (fs/path name))
        (with-open [writer (io/writer (fs/file sr-yaml))]
          (yaml/generate-stream
           writer
           {:db "sink.jsonl"}
           :dumper-options {:flow-style :block}))
        success))))

(defn project-config [request]
  (let [{:keys [project-name]} (-> request ::re/match :path-params)
        path (some-> project-name (fs/path "sr.yaml"))]
    (if-not (fs/exists? path)
      not-found
      (let [config (-> path fs/file io/reader yaml/parse-stream
                       json/write-str (json/read-str :key-fn keyword))]
        {:body config}))))

(defn add-data [{:keys [by-hash raw] :as data} {:keys [hash type] :as item}]
  (if (get by-hash hash)
    data
    (cond-> (assoc data
                   :by-hash (assoc (or by-hash {}) hash item)
                   :raw (conj (or raw []) item))

      (= "label-answer" type)
      (update-in [:doc-to-answers (-> item :data :document)]
                 (fnil conj []) item))))

(defn load-data [file]
  (try
    (let [items (->> file fs/file io/reader line-seq distinct
                     (map #(json/read-str % :key-fn keyword)))]
      (reduce add-data {} items))
    (catch java.io.FileNotFoundException _)))

(defn load-project [projects name]
  (or
   (get @projects name)
   (let [config-file (fs/path name "sr.yaml")
         config (-> config-file fs/file slurp yaml/parse-string)
         db-file (->> config :db (fs/path name))
         events (load-data db-file)]
     (swap! projects assoc name
            {:config config
             :config-file config-file
             :db-file db-file
             :events events}))))

(defn add-events! [dtm data-file events]
  (locking write-lock
    (with-open [writer (io/writer data-file :append true)]
      (doseq [{:keys [hash] :as item} events]
        (when-not (get (:by-hash @dtm) hash)
          (json/write item writer)
          (.write writer "\n")
          (swap! dtm add-data item))))))

(defn handle-tail-line [dtm db http-port line]
  (let [{:keys [data type] :as event} (json/read-str line :key-fn keyword)]
    (if (= "control" type)
      (when (:http-port data)
        (deliver http-port (:http-port data)))
      (add-events! dtm db [event]))))

(defn get-documents [request projects]
  (let [{:keys [project-name]} (-> request ::re/match :path-params)
        {:keys [events]} (load-project projects project-name)]
    {:status 200
     :body (->> events :raw
                (filter (comp #{"document"} :type)))}))

(defn get-recent-events [request projects]
  (let [{:keys [project-name]} (-> request ::re/match :path-params)
        {:keys [events]} (load-project projects project-name)
        recent-events (some->> events :raw rseq (take 100))]
    {:status 200
     :body (vec recent-events)}))

(defn hash [request projects]
  (let [{:keys [id project-name]} (-> request ::re/match :path-params)
        {:keys [events]} (load-project projects project-name)
        event (get (:by-hash events) id)]
    (if event
      {:body event}
      not-found)))

(defn hashes [request dtm]
  (server/as-channel
   request
   {:on-open (fn [ch]
               (server/send! ch {:status 200} false)
               (doseq [{:keys [hash]} (:raw @dtm)]
                 (server/send! ch (str (json/write-str hash) "\n") false))
               (server/close ch))}))

(defn doc-answers [request projects]
  (let [{:keys [id project-name]} (-> request ::re/match :path-params)
        {:keys [events]} (load-project projects project-name)
        event (get (:by-hash events) id)]
    (when event
      {:body (-> events :doc-to-answers (get id))})))

(defn upload [request projects]
  (some->> request :body io/reader line-seq
           (map #(json/read-str % :key-fn keyword))
           #_(add-events! dtm data-file))
  success)

(defn routes [{:keys [projects]}]
  (let [;; Allow hot-reloading in dev when handler is a var.
        ;; reitit does not natively understand vars.
        h (fn [handler] (fn [request] (handler request)))]
    ["/api/v1" {:middleware [mw/wrap-format]}
     ["/project" {:get (h #'get-projects)
                  :post (h #'POST-project)}]
     ["/project/:project-name"
      ["/config" {:get (h #'project-config)}]
      ["/document" {:get #(get-documents % projects)}]
      ["/document/:id/label-answers" {:get #(doc-answers % projects)}]
      ["/hash/:id" {:get #(hash % projects)}]
      ["/hashes" {:get #(hashes % projects)}]
      ["/recent-events" {:get #(get-recent-events % projects)}]
      ["/upload" {:post #(upload % projects)}]]]))
