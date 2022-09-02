(ns srvc.server
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [donut.system :as ds]
            [org.httpkit.server :as server]
            [reitit.ring :as rr]
            [ring.middleware.session :refer (wrap-session)]
            [srvc.server.api :as api]
            [srvc.server.html :as html]
            [srvc.server.review :as review]
            [srvc.server.saml :as saml]))

(defn routes [dtm data-file proxy-host]
  [(html/routes dtm proxy-host)
   (api/routes dtm data-file)
   (saml/routes "http://127.0.0.1:8090")])

(defn load-data [filename]
  (try
    (let [items (->> filename io/reader line-seq distinct
                     (map #(json/read-str % :key-fn keyword)))]
      (reduce api/add-data {} items))
    (catch java.io.FileNotFoundException _)))

(defn default-handler []
  (rr/create-resource-handler
   {:not-found-handler
    (rr/create-default-handler
     {:not-found html/not-found})
    :path "/"}))

(defonce state (atom nil))

(defn handle-tail-line [dtm db http-port line]
  (let [{:keys [data type] :as event} (json/read-str line :key-fn keyword)]
    (if (= "control" type)
      (when (:http-port data)
        (deliver http-port (:http-port data)))
      (api/add-events! dtm db [event]))))

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
