(ns srvc.server.api
  (:refer-clojure :exclude [hash])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [org.httpkit.server :as server]
            [reitit.core :as re]))

(defonce write-lock (Object.))

(defn add-data [{:keys [by-hash raw] :as data} {:keys [hash type] :as item}]
  (if (get by-hash hash)
    data
    (cond-> (assoc data
                   :by-hash (assoc (or by-hash {}) hash item)
                   :raw (conj (or raw []) item))

      (= "label-answer" type)
      (update-in [:doc-to-answers (-> item :data :document)]
                 (fnil conj []) item))))

(defn add-events! [dtm data-file events]
  (locking write-lock
    (with-open [writer (io/writer data-file :append true)]
      (doseq [{:keys [hash] :as item} events]
        (when-not (get (:by-hash @dtm) hash)
          (json/write item writer)
          (.write writer "\n")
          (swap! dtm add-data item))))))

(defn hash [request dtm]
  (let [id (-> request ::re/match :path-params :id)
        item (-> @dtm :by-hash (get id))]
    (when item
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str item)})))

(defn hashes [request dtm]
  (server/as-channel
   request
   {:on-open (fn [ch]
               (server/send! ch {:status 200} false)
               (doseq [{:keys [hash]} (:raw @dtm)]
                 (server/send! ch (str (json/write-str hash) "\n") false))
               (server/close ch))}))

(defn doc-answers [request dtm]
  (let [id (-> request ::re/match :path-params :id)
        data @dtm
        item (-> data :by-hash (get id))]
    (when item
      (server/as-channel
       request
       {:on-open (fn [ch]
                   (server/send! ch {:status 200} false)
                   (doseq [answer (-> data :doc-to-answers (get id))]
                     (server/send! ch (str (json/write-str answer) "\n") false))
                   (server/close ch))}))))

(defn upload [request dtm data-file]
  (some->> request :body io/reader line-seq
           (map #(json/read-str % :key-fn keyword))
           (add-events! dtm data-file))
  {:status 201
   :headers {"Content-Type" "application/json"}
   :body "{\"success\":true}"})

(defn routes [dtm data-file]
  ["/api/v1"
   ["/document/:id/label-answers" {:get #(doc-answers % dtm)}]
   ["/hash/:id" {:get #(hash % dtm)}]
   ["/hashes" {:get #(hashes % dtm)}]
   ["/upload" {:post #(upload % dtm data-file)}]])
