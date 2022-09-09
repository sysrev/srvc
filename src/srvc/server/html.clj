(ns srvc.server.html
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [lambdaisland.uri :as uri]
            [org.httpkit.client :as client]
            [reitit.core :as re]
            [reitit.ring.middleware.parameters :refer [parameters-middleware]]
            [rum.core :as rum :refer [defc]]
            [srvc.server.review :as review]))

(def re-project-name
  #"[A-Za-z](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}")

(defn redirect-after-post [url]
  {:status 303
   :headers {"Location" url}})

(defn api-url [request path]
  (str (:api-base request "http://localhost:8090") "/api/v1" path))

(defn parse-json-body [{:keys [body] :as response}]
  (assoc response
         :body (json/read (io/reader body) :key-fn keyword)))

(defn json-get [request path]
  (let [{:keys [body status] :as response}
        @(client/get
          (api-url request path)
          {:as :stream
           :timeout 5000}
          parse-json-body)]
    (if (= 200 status)
      body
      (throw (ex-info "Unexpected response" {:response response})))))

(defn get-event [request project-name hash]
  (json-get request (str "/project/" project-name "/hash/" hash)))

(defn get-projects [request]
  (:projects (json-get request "/project")))

(defn project-GET [request project-name path]
  (client/get
   (api-url request (str "/project/" project-name path))
   {:as :stream
    :timeout 5000}
   parse-json-body))

(defn create-project [request name]
  (let [{:keys [status] :as response}
        @(client/post
          (api-url request "/project")
          {:as :stream
           :body (json/write-str {:name name})
           :headers {"Accept" "application/json"
                     "Content-Type" "application/json"}
           :timeout 5000}
          parse-json-body)]
    (when-not (and status (<= 200 status 299))
      (throw (ex-info "Unexpected response" {:response response})))))

(defc head []
  [:head
   [:meta {:charset "UTF-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   [:script {:src "/js/tailwind-3.1.3.min.js"}]
   [:script {:src "/js/htmx-1.7.0.min.js"}]])

(defc page [body]
  [:html
   (head)
   body])

(defn response [body]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (list "<!doctype html>" (rum/render-static-markup (page body)))})

(defc body [{:keys [::re/match session] :as request} & content]
  (let [{:keys [project-name]} (:path-params match)
        {:keys [saml/email]} session
        project-url #(str "/p/" project-name %)]
    [:body {:class "bg-slate-100 dark:bg-slate-900 text-slate-900 dark:text-slate-100"}
     [:div {:class "flex h-screen"}
      [:div {:class "h-screen w-64 pl-4 pt-4 text-lg text-slate-100 bg-slate-900"}
       (-> (when project-name
             [[:a {:href (project-url "/activity")} "Activity"]
              [:a {:href (project-url "/documents")} "Documents"]
              [:a {:href (project-url "/review")} "Review"]])
           (concat
            [(if email
               [:a {:href "/saml/logout"} "Log Out (" email ")"]
               [:a {:href "/saml/login"} "Log In"])])
           (->> (map #(vector :li %))
                (into [:ul])))
       [:hr {:class "m-4"}]
       (let [projects (get-projects request)]
         (when (seq projects)
           [:div
            [:a {:href "/"} "Projects: " [:span {:class "font-bold"} "+"]]
            [:div
             (->> projects
                  (mapv #(do [:li [:a {:href (str "/p/" % "/documents")} %]]))
                  (into [:ul]))]]))]
      [:div {:class "flex-1 flex flex-col overflow-hidden pt-4 ml-4"}
       content]]]))

(defn not-found [request]
  {:status 404
   :headers {"Content-Type" "text/html"}
   :body (list "<!doctype html>"
               (rum/render-static-markup
                (page
                 (body
                  request
                  [:div [:h1 {:class "text-2xl text-bold text-gray-700 bg-gray-50 dark:bg-gray-700 dark:text-gray-400"}
                         "404 Not Found"]]))))})

(defn server-error [request]
  {:status 404
   :headers {"Content-Type" "text/html"}
   :body (list "<!doctype html>"
               (rum/render-static-markup
                (page
                 (body
                  request
                  [:div [:h1 {:class "text-2xl text-bold text-gray-700 bg-gray-50 dark:bg-gray-700 dark:text-gray-400"}
                         "500 Internal Server Error"]]))))})

(defc table-head [col-names]
  [:thead {:class "text-xs text-gray-700 uppercase bg-gray-50 dark:bg-gray-700 dark:text-gray-400"}
   [:tr
    (map #(vector :th {:scope "col" :class "px-6 py-3"} %) col-names)]])

(defc table-row [row]
  [:tr {:class "border-b dark:bg-gray-800 dark:border-gray-700 odd:bg-white even:bg-gray-50 odd:dark:bg-gray-800 even:dark:bg-gray-700"}
   [:th {:scope "row" :class "px-6 py-4 font-medium text-gray-900 dark:text-white whitespace-nowrap"}
    (first row)]
   (map #(vector :td {:class "px-6 py-4"} %) (rest row))])

(defc table-body [rows]
  [:tbody
   (map table-row rows)])

(defc table [col-names rows]
  [:div {:class "relative overflow-x-auto shadow-md sm:rounded-lg"}
   [:table {:class "w-full text-sm text-left text-gray-500 dark:text-gray-400"}
    (table-head col-names)
    (table-body rows)]])

(defn validate-project-name [request _form _field name]
  (let [name (some-> name str/trim)
        err #(do {:error %
                  :valid? false
                  :value name})]
    (cond
      (empty? name) (err "Required.")

      (not (re-matches re-project-name name))
      (err "Invalid project name.")

      (some (partial = name) (get-projects request))
      (err "There is already a project with that name.")

      :else
      {:valid? true
       :value name})))

(defn validate-form [request form]
  (let [{:keys [params]} request]
    (->> form :fields
         (map (fn [{:keys [id validate] :as field}]
                [id (validate request form field (get params id))]))
         (into {}))))

(defn validate-url [form field]
  (str "/hx/validate/" (:id form) "/" (:id field)))

(defc form-input [form {:keys [id label] :as field}
                  & [{:keys [error] :as validation}]]
  [:div {:hx-swap "outerHTML" :hx-target "this"}
   [:label {:for id} label]
   [:input {:class ["ml-4" "dark:text-slate-900"]
            :hx-post (validate-url form field)
            :id id
            :name id
            :value (if validation (:value validation) (:value field))}]
   (if error
     [:div {:class ["text-red-500"]}
      error]
     [:div {:style {:height "1em"}}])])

(defc form [request
            {:keys [extra-content fields] :as form}
            & [validations]]
  [:form {:method "post"}
   (map
    (fn [{:keys [id] :as field}]
      (form-input form field (get validations id))) fields)
   (extra-content request)])

(def create-project-form
  {:extra-content (fn [_request]
                    (list
                     [:br]
                     [:input {:type "submit" :value "Create"}]))
   :fields [{:id "project-name"
             :label "Project Name"
             :validate validate-project-name}]
   :id "create-project"})

(def forms
  (->> [create-project-form]
       (map (juxt :id identity))
       (into {})))

(defn home [{:keys [form-params] :as request}]
  (let [projects (get-projects request)
        validations (when (seq form-params)
                      (validate-form request create-project-form))]
    (response
     (body
      request
      [:div
       [:h2.text-lg.font-bold "Create Project"]
       (form request create-project-form validations)
       (when (seq projects)
         [:div.mt-4
          [:h2.text-lg.font-bold "Projects"]
          [:div
           (->> projects
                (mapv #(do [:li [:a {:href (str "/p/" % "/documents")} %]]))
                (into [:ul]))]])]))))

(defn POST-home [request]
  (let [validations (validate-form request create-project-form)]
    (if (every? :valid? (vals validations))
      (do (create-project request (-> validations (get "project-name") :value))
          (redirect-after-post "/"))
      (home request))))

(defn doc-title [{:keys [data uri]}]
  (or (get-in data [:ProtocolSection :IdentificationModule :OfficialTitle])
      uri
      (json/write-str data)))

(defn documents [request]
  (let [{:keys [project-name]} (-> request ::re/match :path-params)
        documents (json-get request (str "/project/" project-name "/document"))]
    (response
     (body
      request
      (table ["Document" "Inclusion"]
             (map (fn [doc]
                    [(doc-title doc) "Yes"])
                  documents))))))

(defc answer-table [request project-name doc-hash reviewer]
  (let [answers (->> (json-get request (str "/project/" project-name "/document/" doc-hash "/label-answers"))
                     (filter #(-> % :data :reviewer (= reviewer))))]
    (table ["Label" "Answer"]
           (for [{{:keys [answer label]} :data} answers]
             [(-> (get-event request project-name label) :data :question)
              (if (string? answer) answer (pr-str answer))]))))

(defn user-display [user-uri]
  (some-> user-uri uri/uri (assoc :scheme nil) str))

(defn recent-event-seq [request]
  (let [{:keys [project-name]} (-> request ::re/match :path-params)
        recent-events (json-get request (str "/project/" project-name "/recent-events"))]
    (distinct
     (for [{:keys [data type] :as event} recent-events]
       [(case type
          "document" (str "New document: " (doc-title event))
          "label" (str "New label: " (:question data))
          "label-answer" (let [{:keys [document reviewer]} data]
                           [:div (str (user-display reviewer)
                                      " labeled "
                                      (->> document
                                           (get-event request project-name)
                                           doc-title))
                            (answer-table request project-name document reviewer)])
          (pr-str event))]))))

(defc activity-table [request]
  [:div#activity-table
   (table ["Event"]
          (take 10 (recent-event-seq request)))])

(defn activity [request]
  (let [{:keys [project-name]} (-> request ::re/match :path-params)]
    (response
     (body
      request
      [:div {:hx-get (str "/hx/" project-name "/activity")
             :hx-trigger "every 1s"}
       (activity-table request)]))))

(defn hx-response [hiccup]
  {:status 200
   :body (rum/render-static-markup hiccup)})

(defn hx-activity [request]
  (hx-response (activity-table request)))

(defn hx-validate-form-field [{:keys [::re/match params] :as request}]
  (let [{:keys [field-id form-id]} (:path-params match)
        {:keys [fields] :as form} (get forms form-id)
        {:keys [validate] :as field} (some #(when (= field-id (:id %)) %) fields)]
    (when (and field field-id)
      (hx-response
       (form-input
        form field
        (validate request form field (get params field-id)))))))

(defn handle-tail-line [http-port-promise line]
  (let [{:keys [data type]} (json/read-str line :key-fn keyword)
        {:keys [http-port]} data]
    (when (and http-port (= "control" type))
      (deliver http-port-promise http-port))))

(defn load-review-process [{:keys [session]} review-processes project-name project-config flow-name]
  (let [{:keys [saml/email]} session
        k [project-name flow-name email]]
    (or
     (get @review-processes k)
     (let [http-port-promise (promise)
           process (-> (review/review-process
                        project-name
                        project-config
                        flow-name
                        (partial handle-tail-line http-port-promise)
                        prn)
                       (assoc :http-port-promise http-port-promise))]
       (swap! review-processes assoc k process)
       process))))

(defn review [request]
  (let [{:keys [project-name]} (-> request ::re/match :path-params)
        resp @(project-GET request project-name "/config")]
    (case (:status resp)
      404 (not-found request)
      200 (response
           (body
            request
            [:div
             [:h2 "Flows:"]
             [:ul
              (map
               #(do [:li
                     [:a {:href (str "/p/" project-name "/review/" %)}
                      %]])
               (->> resp :body :flows keys
                    (map name)
                    (sort-by str/lower-case)))]]))
      (server-error request))))

(defn review-flow [{:keys [::re/match scheme session] :as request}
                   {:keys [proxy-config review-processes]}]
  (let [{:keys [flow-name project-name]} (:path-params match)
        {:keys [status] :as resp} @(project-GET request project-name "/config")
        flow (-> resp :body :flows (get (keyword flow-name)))]
    (cond
      (or (= 404 status) (not flow)) (not-found request)
      (not= 200 status) (server-error request)
      :else
      (let [process (load-review-process
                     request review-processes
                     project-name (:body resp)
                     flow-name)
            proxy-url (str "http://localhost:" @(:http-port-promise process))]
        (-> (response
             (body
              request
              [:iframe {:class ["w-full" "bg-white"]
                        :style {:height "100vh"}
                        :src (str (name scheme) "://" (:host proxy-config)
                                  ":" (first (:listen-ports proxy-config)))}]))
            (assoc :session (assoc session :review-proxy-url proxy-url)))))))

(defn routes [config]
  (let [;; Allow hot-reloading in dev when handler is a var.
        ;; reitit does not natively understand vars.
        h (fn [handler] (fn [request] (handler request)))]
    [["/" {:get (h #'home)
           :middleware [parameters-middleware]
           :post (h #'POST-home)}]
     ["/" {:middleware [parameters-middleware]}
      ["p/:project-name"
       ["/activity" {:get (h #'activity)}]
       ["/documents" {:get (h #'documents)}]
       ["/review" {:get (h #'review)}]
       ["/review/:flow-name" {:get #(review-flow % config)}]]
      ["hx"
       ["/validate/:form-id/:field-id"
        {:post (h #'hx-validate-form-field)}]
       ["/:project-name"
        ["/activity" {:get (h #'hx-activity)}]]]]]))
