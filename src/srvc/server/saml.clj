(ns srvc.server.saml
  (:require [reitit.ring.middleware.parameters :refer (parameters-middleware)]
            [saml20-clj.core :as saml]
            ;[saml20-clj.coerce :as saml-coerce]
            [saml20-clj.encode-decode :as saml-decode]))

(def assertion-validators [:signature #_:require-encryption :recipient :issuer
                           :not-on-or-after :not-before :in-response-to :address])

(def response-validators [:signature :require-signature :issuer :valid-request-id])

(defn config [host]
  {:app-name "srvc"
   :acs-url (str host "/saml/acs")
   :slo-url (str host "/saml/slo")})

#_(def credentials {:alias "my-saml-secrets"
                  :filename "keystorefile.jks"
                  :password "s1krit"})

(defn metadata [{:keys [host]} _request]
  (let [xmlstr (-> {} #_{:sp-cert (saml-coerce/->X509Certificate credentials)}
                   (merge (config host))
                   saml/metadata)]
    {:headers {"Content-Type" "application/xml"}
     :body xmlstr}))

(defn login [{:keys [host idp-url state-manager]} _request]
  (-> (saml/request
       {:sp-name "srvc"
        :acs-url (str host "/saml/acs")
        :idp-url idp-url
        :issuer (str host "/saml/metadata")
        :request-id (str (random-uuid))
        :state-manager state-manager
      ;; :credential is optional. If passed, sign the request with this key and attach public key data, if present
        #_#_:credential       sp-private-key})
      (saml/idp-redirect-response
       idp-url
       (str host "/"))))

(defn logout [_opts {:keys [session]}]
  {:status 302
   :headers {"Location" "/"}
   :session (dissoc session :saml/email :saml/groups :saml/uid)})

(defn request-options [host state-manager]
  {:acs-url (str host "/saml/acs")
   :state-manager state-manager
   :solicited? true
   :allowable-clock-skew-seconds 180
   :user-agent-address nil
   :issuer nil
   :response-validators response-validators
   :assertion-validators assertion-validators})

(defn acs [{:keys [host state-manager]} request]
  (let [{:strs [RelayState SAMLResponse]} (:params request)
        assertions (-> SAMLResponse
                       saml-decode/base64->str
                       saml/->Response
                       (saml/validate nil #_idp-cert nil #_sp-private-key (request-options host state-manager))
                       saml/assertions)
        {:strs [email eduPersonAffiliation uid]} (-> assertions first :attrs)]
    {:status 302
     :headers {"Location" RelayState}
     :session #:saml{:email (first email)
                     :groups eduPersonAffiliation
                     :uid (first uid)}}))

(defn routes [host]
  (let [opts {:host host
              :idp-url "http://localhost:8080/simplesaml/saml2/idp/SSOService.php"
              :state-manager (saml/in-memory-state-manager)}]
    [["/saml"
      {:middleware [parameters-middleware]}
      ["/acs" {:post (partial acs opts)}]
      ["/login" {:get (partial login opts)}]
      ["/logout" {:get (partial logout opts)}]
      ["/metadata" {:get (partial metadata opts)}]]]))
