(ns hash
  (:require [babashka.deps :as deps]))

(deps/add-deps '{:deps {co.insilica/canonical-json
                        {:git/sha "3981de518b23adbfcb5116e99a9decef625eeb3f"
                         :git/url "https://github.com/insilica/canonical-json"}
                        co.insilica/clj-multihash
                        {:git/sha "7cd4d1fc1301aeea36d5ba80e65ebe2fdbaaa392"
                         :git/url "https://github.com/insilica/clj-multihash.git"}}})

(require '[insilica.canonical-json :as json]
         '[multihash.core :as multihash]
         '[multihash.digest :as digest])

(defn hash [m]
  (-> (dissoc m :hash) json/write-str digest/sha2-256 multihash/base58))

(defn add-hash [m]
  (assoc m :hash (hash m)))
