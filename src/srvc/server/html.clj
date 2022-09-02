(ns srvc.server.html
  (:require [clojure.data.json :as json]
            [hiccup.core :as h]
            [lambdaisland.uri :as uri]
            [org.httpkit.server :as server]))

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
    [:body {:class "bg-slate-100 dark:bg-slate-900"}
     [:div {:class "flex h-screen"}
      [:div
       [:ul {:class "h-screen w-64 pl-4 pt-4 text-lg text-slate-100 bg-slate-900"}
        [:li [:a {:href "/activity"} "Activity"]]
        [:li [:a {:href "/"} "Articles"]]
        [:li [:a {:href "/review"} "Review"]]
        (if email
          [:li [:a {:href "/saml/logout"} "Log Out (" email ")"]]
          [:li [:a {:href "/saml/login"} "Log In"]])]]
      [:div {:class "flex-1 flex flex-col overflow-hidden pt-4"}
       content]]]))

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

(defn review [request proxy-host]
  (response
   (body
    request
    [:iframe {:class "w-full bg-white"
              :style "height: 90vh"
              :src proxy-host}])))

(defn routes [dtm proxy-host]
  [["/" {:get #(articles % dtm)}]
   ["/activity" {:get #(activity % dtm)}]
   ["/review" {:get #(review % proxy-host)}]
   ["/hx"
    ["/activity" {:get #(hx-activity % dtm)}]]])
