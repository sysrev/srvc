(require 'hashp.core)

(ns srvc.server
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [donut.system :as ds]
            [org.httpkit.server :as server]
            [reitit.ring :as rr]
            [ring.middleware.session :as session]
            [ring.middleware.session.memory :as mem]
            [srvc.server.api :as api]
            [srvc.server.html :as html]
            [srvc.server.review :as review]
            [srvc.server.saml :as saml]))

(defn routes [config]
  [(html/routes config)
   (api/routes config)
   (saml/routes "http://localhost:8090")])

(defn default-handler []
  (rr/create-resource-handler
   {:not-found-handler
    (rr/create-default-handler
     {:not-found html/not-found})
    :path "/"}))

(defonce state (atom nil))

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
  (if-let [resource (or (io/resource filename) (io/file filename))]
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

(defn config-component []
  #::ds{:start (fn [{::ds/keys [config]}]
                 (get-config (:filename config)))
        :stop (constantly nil)
        :config {:filename (ds/local-ref [:env :config-file])}})

(defn http-server-component [config]
  #::ds{:config config
        :start (fn [{:keys [::ds/config]}]
                 (let [{:keys [session-opts]} config
                       wrap-session (fn [handler]
                                      (session/wrap-session handler session-opts))
                       server (server/run-server
                               (-> (routes config)
                                   rr/router
                                   (rr/ring-handler
                                    (default-handler)
                                    {:middleware [wrap-session]}))
                               {:legacy-return-value? false})]
                   {:port (server/server-port server)
                    :server server}))
        :stop (fn [{::ds/keys [instance]}]
                @(server/server-stop! (:server instance))
                nil)})

(defn projects-component []
  #::ds{:start (fn [_]
                 (atom {}))
        :stop (constantly nil)})

(defn session-opts-component []
  #::ds{:start (fn [_]
                 {:cookie-attrs {:http-only true}
                  :cookie-name "ring-session"
                  :root "/"
                  :store (mem/memory-store)})
        :stop (constantly nil)})

(defn system [env]
  {::ds/defs
   {:srvc-server
    {:env {::ds/start (constantly env)}
     :config (config-component)
     :http-server (http-server-component
                   {:host (ds/local-ref [:config :host])
                    :local-auth (ds/local-ref [:config :local-auth])
                    :port (ds/local-ref [:config :port])
                    :projects (ds/local-ref [:projects])
                    :proxy-config (ds/local-ref [:config :proxy])
                    :review-processes (ds/local-ref [:review-processes])
                    :saml (ds/local-ref [:config :saml])
                    :session-opts (ds/local-ref [:session-opts])})
     :projects (projects-component)
     :proxy-server (review/proxy-server-component
                    {:listen-ports (ds/local-ref [:config :proxy :listen-ports])
                     :session-opts (ds/local-ref [:session-opts])})
     :review-processes (review/review-processes-component)
     :session-opts (session-opts-component)}}})

;; Not thread-safe. For use by -main and at REPL
(defn start! []
  (let [env {:config-file "srvc-server-config.edn"}]
    (swap! state #(signal! (or % (system env)) ::ds/start))))

;; Not thread-safe. For use by -main and at REPL
(defn stop! []
  (swap! state #(when % (signal! % ::ds/stop) nil)))

(defn -main []
  (start!)
  (Thread/sleep Long/MAX_VALUE))

(comment
  (do (stop!) (start!)))
