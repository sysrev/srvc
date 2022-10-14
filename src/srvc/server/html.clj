(ns srvc.server.html
  (:require [buddy.hashers :as hashers]
            [clojure.data.json :as json]
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
         :body (some-> body io/reader (json/read :key-fn keyword))))

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

(defn project-POST [request project-name path body]
  (let [{:keys [status] :as response}
        @(client/post
          (api-url request (str "/project/" project-name path))
          {:as :stream
           :body (json/write-str body)
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
        {:keys [email]} session
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
               [:a {:href "/logout"} "Log Out (" email ")"]
               [:a {:href "/login"} "Log In"])])
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

(defn handle-tail-line [request project-name http-port-promise line]
  (let [{:keys [data type] :as event} (json/read-str line :key-fn keyword)
        {:keys [http-port]} data]
    (if (= "control" type)
      (when http-port
        (deliver http-port-promise http-port))
      (project-POST request project-name "/upload" [event]))))

(defn load-review-process
  [{:keys [session] :as request} review-processes project-name project-config flow-name]
  (let [{:keys [email]} session
        k [project-name flow-name email]]
    (or
     (get @review-processes k)
     (let [http-port-promise (promise)
           process (-> (review/review-process
                        project-name
                        project-config
                        flow-name
                        (partial handle-tail-line request project-name http-port-promise)
                        prn
                        (str "mailto:" (:email session)))
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
      (not (:email session)) {:status 302
                              :headers {"Location" "/login"}}
      (or (= 404 status) (not flow)) (not-found request)
      (not= 200 status) (server-error request)
      :else
      (let [process (load-review-process
                     request review-processes
                     project-name (:body resp)
                     flow-name)
            proxy-url (str "http://localhost:" @(:http-port-promise process))]
        {:status 302
         :headers {"Location" (str (name scheme) "://" (:host proxy-config)
                                   ":" (first (:listen-ports proxy-config)))}
         :session (assoc session :review-proxy-url proxy-url)}
        #_(-> (response
             (body
              request
              [:a {;:class ["w-full" "bg-white"]
                        ;:style {:height "100vh"}
                   :href (str (name scheme) "://" (:host proxy-config)
                              ":" (first (:listen-ports proxy-config)))
                   :target "_blank"}
               "Open review in new tab"]))
            (assoc :session (assoc session :review-proxy-url proxy-url)))))))

(defn login [{:keys [params]} & [error]]
  ;; https://tailwindcomponents.com/component/login-showhide-password
  (response
   (list
    [:script {:defer true
              :src "/js/alpine2.js"}]
    [:div {:class "container max-w-full mx-auto py-24 px-6"}
     [:div.font-sans
      [:div {:class "max-w-sm mx-auto px-6"}
       [:div {:class "relative flex flex-wrap"}
        [:div {:class "w-full relative"}
         [:div.mt-6
          [:div {:class "text-center font-semibold text-black"}
           "Log in to srvc"]
          [:form.mt-8 {:method "post"}
           [:div.mx-auto.max-w-lg
            [:div.py-2
             [:label {:class "px-1 text-sm text-gray-600"
                      :for "email"}
              "Email address"]
             [:input {:class "text-md block px-3 py-2  rounded-lg w-full bg-white border-2 border-gray-300 placeholder-gray-600 shadow-md focus:placeholder-gray-500 focus:bg-white focus:border-gray-600 focus:outline-none"
                      :id "email"
                      :name "email"
                      :type "text"
                      :value (get params "email")}]]
            [:div.py-2 {:x-data "{ show: true }"}
             [:label {:class "px-1 text-sm text-gray-600"
                      :for "password"}
              "Password"]
             [:div.relative
              [:input {:class "text-md block px-3 py-2 rounded-lg w-full bg-white border-2 border-gray-300 placeholder-gray-600 shadow-md focus:placeholder-gray-500 focus:bg-white focus:border-gray-600 focus:outline-none"
                       :id "password"
                       :name "password"
                       :type "password"
                       ":type" "show ? 'password' : 'text'"}]
              [:div {:class "absolute inset-y-0 right-0 pr-3 flex items-center text-sm leading-5"}
               [:svg {:class "h-6 text-gray-700"
                      ":class" "{'hidden': !show, 'block':show }"
                      "@click" "show = !show"
                      :fill "none"
                      :viewbox "0 0 576 512"
                      :xmlns "http://www.w3.org/2000/svg"}
                [:path {:d "M572.52 241.4C518.29 135.59 410.93 64 288 64S57.68 135.64 3.48 241.41a32.35 32.35 0 0 0 0 29.19C57.71 376.41 165.07 448 288 448s230.32-71.64 284.52-177.41a32.35 32.35 0 0 0 0-29.19zM288 400a144 144 0 1 1 144-144 143.93 143.93 0 0 1-144 144zm0-240a95.31 95.31 0 0 0-25.31 3.79 47.85 47.85 0 0 1-66.9 66.9A95.78 95.78 0 1 0 288 160z"
                        :fill "currentColor"}]]
               [:svg {:class "h-6 text-gray-700"
                      ":class" "{'block': !show, 'hidden':show }"
                      "@click" "show = !show"
                      :fill "none"
                      :viewbox "0 0 640 512"
                      :xmlns "http://www.w3.org/2000/svg"}
                [:path {:d "M320 400c-75.85 0-137.25-58.71-142.9-133.11L72.2 185.82c-13.79 17.3-26.48 35.59-36.72 55.59a32.35 32.35 0 0 0 0 29.19C89.71 376.41 197.07 448 320 448c26.91 0 52.87-4 77.89-10.46L346 397.39a144.13 144.13 0 0 1-26 2.61zm313.82 58.1l-110.55-85.44a331.25 331.25 0 0 0 81.25-102.07 32.35 32.35 0 0 0 0-29.19C550.29 135.59 442.93 64 320 64a308.15 308.15 0 0 0-147.32 37.7L45.46 3.37A16 16 0 0 0 23 6.18L3.37 31.45A16 16 0 0 0 6.18 53.9l588.36 454.73a16 16 0 0 0 22.46-2.81l19.64-25.27a16 16 0 0 0-2.82-22.45zm-183.72-142l-39.3-30.38A94.75 94.75 0 0 0 416 256a94.76 94.76 0 0 0-121.31-92.21A47.65 47.65 0 0 1 304 192a46.64 46.64 0 0 1-1.54 10l-73.61-56.89A142.31 142.31 0 0 1 320 112a143.92 143.92 0 0 1 144 144c0 21.63-5.29 41.79-13.9 60.11z"
                        :fill "currentColor"}]]]]]
            [:div {:class "flex justify-between"}
             [:label {:class "block text-gray-500 font-bold my-4"}
              [:input {:checked (contains? params "rememberme")
                       :class "leading-loose text-pink-600"
                       :id "rememberme"
                       :name "rememberme"
                       :type "checkbox"}]
              [:span {:class "py-2 text-sm text-gray-600 leading-snug"}
               " Remember me"]]]
            [:div {:class "text-red-600 font-bold"}
             error]
            [:button {:class "mt-3 text-lg font-semibold  bg-gray-800 w-full text-white rounded-lg px-6 py-3 block shadow-xl hover:text-white hover:bg-black"}
             "Log in"]]]]]]]]])))

(defn POST-login [{:keys [params session] :as request} {:keys [local-auth]}]
  (let [{:strs [email password rememberme]} params
        email (some-> email str/trim str/lower-case)]
    (cond
      (not (seq email))
      (login request)
      
      (some->> local-auth :users
               (filter #(= email (str/lower-case (:email %))))
               first :password
               (hashers/verify password)
               :valid)
      {:status 302
       :headers {"Location" "/"}
       :session (assoc session :email email)}
      
      :else
      (login request "Wrong email or password"))))

(defn logout [{:keys [session]}]
  {:status 302
   :headers {"Location" "/"}
   :session (dissoc session :email)})

(defn routes [config]
  (let [;; Allow hot-reloading in dev when handler is a var.
        ;; reitit does not natively understand vars.
        h (fn [handler] (fn [request] (handler request)))]
    [["/" {:get (h #'home)
           :middleware [parameters-middleware]
           :post (h #'POST-home)}]
     ["/" {:middleware [parameters-middleware]}
      ["login" {:get (h #'login)
                :post #(POST-login % config)}]
      ["logout" {:get (h #'logout)}]
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
