#!/usr/bin/env bb

(require '[babashka.process :as p])

(def default-opts
  {:err *err*
   :shutdown p/destroy-tree})

(defn process [args & [opts]]
  (p/process args (merge default-opts opts)))

(let [gen (process ["bb" "gen/gen.clj"])
      map (process ["bb" "map/map.clj"] {:in (:out gen)})
      sink (process ["bb" "sink/sink.clj"] {:in (:out map) :out *out*})]
  @sink)

nil
