(ns srvc.server.review
  (:require [babashka.process :as p]
            [clojure.data.json :as json]))

(def default-opts
  {:in nil
   :err :inherit
   :out :inherit
   :shutdown p/destroy-tree})

(defn process [args & [opts]]
  (p/process args (merge default-opts opts)))

(defn load-config [filename]
  (let [{:keys [err exit out]} @(process ["sr" "--config" filename "print-config"]
                                         {:err :string :out :string})]
    (if (zero? exit)
      (json/read-str out :key-fn keyword)
      (throw (ex-info "sr print-config failed"
                      {:err err
                       :exit exit
                       :filename filename
                       :out out})))))
