(ns srvc.server
  (:require [clojure.java.io :as io]
            [hiccup.core :as h]
            [insilica.canonical-json :as json]
            [org.httpkit.server :as server]
            [reitit.ring :as rr]))

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

(defn articles [data]
  (response
   [:body {:class "dark:bg-gray-900"}
    (table ["Document" "Inclusion"]
           (article-rows data))]))

(defn routes [data]
  [["/" {:get #(do % (articles data))}]])

(defn load-data [filename]
  (let [raw (->> filename io/reader line-seq distinct
                 (mapv #(json/read-str % :key-fn keyword)))
        by-hash (into {} (map (juxt :hash identity) raw))]
    {:by-hash by-hash :raw raw}))

(defn start! [data-file]
  (let [data (load-data data-file)]
    (server/run-server #((-> data routes rr/router rr/ring-handler) %))))

(defn -main [data-file]
  (start! data-file)
  (Thread/sleep Long/MAX_VALUE))
