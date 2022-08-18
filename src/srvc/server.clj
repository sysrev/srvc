(ns srvc.server
  (:refer-clojure :exclude [hash])
  (:require [babashka.process :as p]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [donut.system :as ds]
            [hiccup.core :as h]
            [lambdaisland.uri :as uri]
            [org.httpkit.server :as server]
            [reitit.core :as re]
            [reitit.ring :as rr]
            [ring.middleware.session :refer (wrap-session)]
            [srvc.server.review :as review]
            [srvc.server.saml :as saml]))

(defonce write-lock (Object.))

(defn head []
  [:head
   [:meta {:charset "UTF-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   [:script {:src "/js/tailwind-3.1.3.min.js"}]
   [:script {:src "/js/htmx-1.7.0.min.js"}]])

(defn page [body]
  [:html
   (head)
   body])

(defn response [body]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (list "<!doctype html>" (h/html (page body)))})

(defn body [{:keys [session]} & content]
  (let [{:keys [saml/email]} session]
    (into
     [:body {:class "dark:bg-gray-900"}
      [:div
       [:ul {:class "text-gray-700 bg-gray-50 dark:bg-gray-700 dark:text-gray-400"}
        [:li [:a {:href "/activity"} "Activity"]]
        [:li [:a {:href "/"} "Articles"]]
        [:li [:a {:href "/review"} "Review"]]
        (if email
          [:li [:a {:href "/saml/logout"} "Log Out (" email ")"]]
          [:li [:a {:href "/saml/login"} "Log In"]])]]]
     content)))

(defn not-found [request]
  {:status 404
   :headers {"Content-Type" "text/html"}
   :body (list "<!doctype html>"
               (h/html
                (page
                 (body
                  request
                  [:div [:h1 {:class "text-2xl text-bold text-gray-700 bg-gray-50 dark:bg-gray-700 dark:text-gray-400"}
                         "404 Not Found"]]))))})

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

(defn doc-title [{:keys [data uri]}]
  (or (get-in data [:ProtocolSection :IdentificationModule :OfficialTitle])
      uri
      (json/write-str data)))

(defn article-rows [{:keys [raw]}]
  (->> (filter (comp #{"document"} :type) raw)
       (map (fn [doc]
              [(doc-title doc) "Yes"]))))

(defn articles [request dtm]
  (response
   (body
    request
    (table ["Document" "Inclusion"]
           (article-rows @dtm)))))

(defn answer-table [{:keys [by-hash doc-to-answers]} doc-hash reviewer]
  (table ["Label" "Answer"]
         (for [{{:keys [answer label]} :data} (->> (doc-to-answers doc-hash)
                                                   (filter #(-> % :data :reviewer
                                                                (= reviewer))))]
           [(-> label by-hash :data :question)
            (if (string? answer) answer (pr-str answer))])))

(defn user-display [user-uri]
  (some-> user-uri uri/uri (assoc :scheme nil) str))

(defn event-seq [{:keys [by-hash raw] :as dt}]
  (distinct
   (for [{:keys [data type] :as item} (some-> raw rseq)]
     [(case type
        "document" (str "New document: " (doc-title item))
        "label" (str "New label: " (:question data))
        "label-answer" (let [{:keys [document reviewer]} data]
                         [:div (str (user-display reviewer)
                                    " labeled "
                                    (-> document by-hash doc-title))
                          (answer-table dt document reviewer)])
        (pr-str item))])))

(defn event-table [data]
  [:div#event-table
   (table ["Event"]
          (take 10 (event-seq data)))])

(defn activity [request dtm]
  (response
   (body
    request
    [:div {:hx-ws "connect:/hx/activity"}
     (event-table @dtm)])))

(defn hx-activity [request dtm]
  (let [watch-key (str (random-uuid))]
    (server/as-channel
     request
     {:on-close (fn [_ _] (remove-watch dtm watch-key))
      :on-open (fn [ch]
                 (server/send! ch {:status 200 :body (h/html (event-table @dtm))} false)
                 (add-watch dtm watch-key
                            (fn [_ _ _ data]
                              (server/send! ch {:body (h/html (event-table data))} false))))})))

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

(defn upload [request dtm data-file]
  (some->> request :body io/reader line-seq
           (map #(json/read-str % :key-fn keyword))
           (add-events! dtm data-file))
  {:status 201
   :headers {"Content-Type" "application/json"}
   :body "{\"success\":true}"})

(defn review [request proxy-host]
  (response
   (body
    request
    [:iframe {:class "w-full bg-white"
              :style "height: 90vh"
              :src proxy-host}])))

(defn routes [dtm data-file proxy-host]
  (into
   [["/" {:get #(articles % dtm)}]
    ["/activity" {:get #(activity % dtm)}]
    ["/review" {:get #(review % proxy-host)}]
    ["/hx"
     ["/activity" {:get #(hx-activity % dtm)}]]
    ["/api/v1"
     ["/document/:id/label-answers" {:get #(doc-answers % dtm)}]
     ["/hash/:id" {:get #(hash % dtm)}]
     ["/hashes" {:get #(hashes % dtm)}]
     ["/upload" {:post #(upload % dtm data-file)}]]]
   (saml/routes "http://127.0.0.1:8090")))

(defn load-data [filename]
  (try
    (let [items (->> filename io/reader line-seq distinct
                     (map #(json/read-str % :key-fn keyword)))]
      (reduce add-data {} items))
    (catch java.io.FileNotFoundException _)))

(defn default-handler []
  (rr/create-resource-handler
   {:not-found-handler
    (rr/create-default-handler
     {:not-found not-found})
    :path "/"}))

(defonce state (atom nil))

(defn handle-tail-line [dtm db http-port line]
  (let [{:keys [data type] :as event} (json/read-str line :key-fn keyword)]
    (if (= "control" type)
      (when (:http-port data)
        (deliver http-port (:http-port data)))
      (add-events! dtm db [event]))))

(defn first-line [s]
  (some-> s not-empty (str/split #"\n") first))

(defn signal! [system signal-name]
  (let [{out ::ds/out :as system} (ds/signal system signal-name)
        {:keys [error validation]} out]
    (cond
      (seq error)
      (throw (ex-info
              (str "Error during " signal-name
                   (some->> error :services first val :message
                            first-line (str ": ")))
              out))

      (seq validation)
      (throw (ex-info
              (str "Validation failed during " signal-name
                   (some->> validation :services first val :message
                            first-line (str ": ")))
              out))

      :else system)))

(defn get-config [filename]
  (if-let [resource (io/resource filename)]
    (with-open [reader (-> resource io/reader java.io.PushbackReader.)]
      (try
        (edn/read reader)
        (catch Exception e
          (throw
           (ex-info (str "Error parsing EDN in config file \"" filename
                         \" ": " (.getMessage e))
                    {:filename filename}
                    e)))))
    (throw
     (ex-info (str "Config file not found: \"" filename "\"")
              {:filename filename}))))

(def config-component
  #::ds{:start (fn [{::ds/keys [config]}]
                 (get-config (:filename config)))
        :stop (constantly nil)
        :config {:filename (ds/local-ref [:env :config-file])}})

(def dtm-component
  #::ds{:start (fn [{::ds/keys [config]}]
                 (atom (load-data (:db config))))
        :stop (constantly nil)
        :config {:db (ds/local-ref [:sr-yaml :db])}})

(def http-server-component
  #::ds{:start (fn [{{:keys [db dtm proxy-host]} ::ds/config}]
                 (let [server (server/run-server
                               (-> (routes dtm db proxy-host)
                                   rr/router
                                   (rr/ring-handler
                                    (default-handler)
                                    {:middleware [wrap-session]}))
                               {:legacy-return-value? false})]
                   {:port (server/server-port server)
                    :server server}))
        :stop (fn [{::ds/keys [instance]}]
                @(server/server-stop! (:server instance))
                nil)
        :config {:db (ds/local-ref [:sr-yaml :db])
                 :dtm (ds/local-ref [:dtm])
                 :host (ds/local-ref [:config :host])
                 :port (ds/local-ref [:config :port])
                 :proxy-host (ds/local-ref [:config :proxy-host])
                 :proxy-port (ds/local-ref [:config :proxy-port])
                 :saml (ds/local-ref [:config :saml])}})

(def sr-yaml-component
  #::ds{:start (fn [{::ds/keys [config]}]
                 (review/load-config (:filename config)))
        :stop (constantly nil)
        :config {:filename (ds/local-ref [:env :sr-yaml-file])}})

(defn system [env]
  {::ds/defs
   {:srvc-server
    {:env {::ds/start (constantly env)}
     :config config-component
     :dtm dtm-component
     :http-server http-server-component
     :sr-yaml sr-yaml-component}}})

;; Not thread-safe. For use by -main and at REPL
(defn start! [flow-name]
  (let [env {:config-file "srvc-server-config.edn"
             :flow-name flow-name
             :sr-yaml-file "sr.yaml"}]
    (swap! state #(signal! (or % (system env)) ::ds/start))))

;; Not thread-safe. For use by -main and at REPL
#_:clj-kondo/ignore
(defn stop! []
  (swap! state #(signal! % ::ds/stop)))

(defn -main [flow-name]
  (start! flow-name)
  (Thread/sleep Long/MAX_VALUE))
