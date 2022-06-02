#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as p])

(def default-opts
  {:inherit true
   :shutdown p/destroy-tree})

(defn process [args & [opts]]
  (p/process args (merge default-opts opts)))

(defn make-fifo [path]
  @(p/process ["mkfifo" (str path)])
  path)

(fs/with-temp-dir [dir {:prefix "srvc"}]
  (let [gen-fifo (-> (fs/path dir "gen.fifo") make-fifo str)
        gen (process ["bb" "gen/gen.clj" gen-fifo])
        remove-reviewed-fifo (-> (fs/path dir "remove-reviewed.fifo") make-fifo str)
        remove-reviewed (process ["bb" "map/remove-reviewed.clj" "sink.db" gen-fifo remove-reviewed-fifo])
        map-fifo (-> (fs/path dir "map.fifo") make-fifo str)
        map (process ["bb" "map/map.clj" remove-reviewed-fifo map-fifo])
        sink (process ["bb" "sink/sink.clj" map-fifo "sink.db"])]
    @sink))

nil
