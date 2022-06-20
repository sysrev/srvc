(ns srvc.server
  (:refer-clojure :exclude [hash])
  (:require [clojure.java.io :as io]
            [hiccup.core :as h]
            [insilica.canonical-json :as json]
            [org.httpkit.server :as server]
            [reitit.core :as re]
            [reitit.ring :as rr]))

(defonce write-lock (Object.))

(defn head []
  [:head
   [:meta {:charset "UTF-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   [:script {:src "https://cdn.tailwindcss.com"}]])

(defn page [body]
  [:html
   (head)
   body])

(defn response [body]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (list "<!doctype html>" (h/html (page body)))})

(defn table-head [col-names]
  [:thead {:class "text-xs text-gray-700 uppercase bg-gray-50 dark:bg-gray-700 dark:text-gray-400"}
   (into [:tr]
         (map #(vector :th {:scope "col" :class "px-6 py-3"} %) col-names))])

(defn table-row [row]
  (into [:tr {:class "border-b dark:bg-gray-800 dark:border-gray-700 odd:bg-white even:bg-gray-50 odd:dark:bg-gray-800 even:dark:bg-gray-700"}
         [:th {:scope "row" :class "px-6 py-4 font-medium text-gray-900 dark:text-white whitespace-nowrap"}
          (first row)]]
        (map #(vector :td {:class "px-6 py-4"} %) (rest row))))

(defn table-body [rows]
  (into [:tbody]
        (map table-row rows)))

(defn table [col-names rows]
  [:div {:class "relative overflow-x-auto shadow-md sm:rounded-lg"}
   [:table {:class "w-full text-sm text-left text-gray-500 dark:text-gray-400"}
    (table-head col-names)
    (table-body rows)]])

(defn doc-title [{:keys [data]}]
  (get-in data [:ProtocolSection :IdentificationModule :OfficialTitle]))

(defn article-rows [{:keys [raw]}]
  (->> (filter (comp #{"document"} :type) raw)
       (map (fn [{:keys [data] :as doc}]
              [(or (doc-title doc) (json/write-str data))
               "Yes"]))))

(defn articles [dtm]
  (response
   [:body {:class "dark:bg-gray-900"}
    (table ["Document" "Inclusion"]
           (article-rows @dtm))]))

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

(defn add-data [{:keys [by-hash raw] :as data} {:keys [hash type] :as item}]
  (if (get by-hash hash)
    data
    (cond-> (assoc data
                   :by-hash (assoc by-hash hash item)
                   :raw (conj raw item))

      (= "label-answer" type)
      (update-in [:doc-to-answers (-> item :data :document)]
                 (fnil conj []) item))))

(defn upload [request dtm data-file]
  (locking [write-lock]
    (with-open [writer (io/writer data-file :append true)]
      (doseq [{:keys [hash] :as item} (some->> request :body
                                               io/reader line-seq
                                               (map #(json/read-str % :key-fn keyword)))]
        (when-not (get (:by-hash @dtm) hash)
          (json/write item writer)
          (.write writer "\n")
          (swap! dtm add-data item)))))
  {:status 201
   :headers {"Content-Type" "application/json"}
   :body "{\"success\":true}"})

(defn routes [dtm data-file]
  [["/" {:get #(do % (articles dtm))}]
   ["/api/v1"
    ["/document/:id/label-answers" {:get #(doc-answers % dtm)}]
    ["/hash/:id" {:get #(hash % dtm)}]
    ["/hashes" {:get #(hashes % dtm)}]
    ["/upload" {:post #(upload % dtm data-file)}]]])

(defn load-data [filename]
  (let [items (->> filename io/reader line-seq distinct
                 (map #(json/read-str % :key-fn keyword)))]
    (reduce add-data
            {:by-hash {} :doc-to-answers {} :raw []}
            items)))

(defn start! [data-file]
  (let [dtm (atom (load-data data-file))]
    (server/run-server #((-> (routes dtm data-file)
                             rr/router rr/ring-handler)
                         %))))

(defn -main [data-file]
  (start! data-file)
  (Thread/sleep Long/MAX_VALUE))