#!/usr/bin/env bb

(require '[babashka.pods :as pods]
         '[cheshire.core :as json]
         '[clojure.java.io :as io])

(pods/load-pod 'org.babashka/go-sqlite3 "0.1.0")
(require '[pod.babashka.go-sqlite3 :as sqlite])

(def schema
  ["create table if not exists sr_data (
      id integer primary key,
      created integer default (strftime('%s', 'now')) not null,
      json text not null,
      uri text
    )"
   "create index if not exists sr_data_uri on sr_data (uri)"
   "create table if not exists sr_label (
      id text primary key
   )"
   ; The definition of a label at a point in time.
   "create table if not exists sr_label_def (
      id integer primary key,
      label_id text not null,
      created integer default (strftime('%s', 'now')) not null,
      question text not null,
      required integer not null,
      type text not null,
      foreign key (label_id) references label (id)
   )"
   "create table if not exists sr_label_answer (
      id integer primary key,
      created integer default (strftime('%s', 'now')) not null,
      data_id integer not null,
      json text not null,
      label_def_id integer not null,
      reviewer text,
      foreign key (data_id) references sr_data (id),
      foreign key (label_def_id) references sr_label_def (id)
   )"])

(defn create-schema [db]
  (doseq [statement schema]
    (sqlite/execute! db statement)))

(defn current-label-def [db id]
  (some-> ["select * from sr_label_def where label_id = ? order by created desc limit 1" id]
          (->> (sqlite/query db))
          first
          (update :required {1 true 0 false})))

(defn update-labels [db labels]
  (doseq [{:keys [id question required type] :as label} labels]
    (->> ["insert or ignore into sr_label (id) values (?)" id]
         (sqlite/execute! db))
    (when-not (= (select-keys label [:question :required :type])
                 (-> (current-label-def db id)
                     (select-keys [:question :required :type])))
      (->> ["insert into sr_label_def
             (label_id, question, required, type)
             values (?,?,?,?)"
            id question required type]
           (sqlite/execute! db)))))

(defn update-data [db data uri]
  (->> ["insert into sr_data (json, uri) values (?,?) returning id"
        (json/generate-string data)
        uri]
       (sqlite/query db)
       first :id))

(defn update-answers [{:keys [db reviewer]} data-id label-answers]
  (doseq [[label-id answer] label-answers]
    (->> ["insert into sr_label_answer (data_id, json, label_def_id, reviewer) values (?,?,?,?)"
          data-id
          (json/generate-string answer)
          (:id (current-label-def db label-id))
          reviewer]
         (sqlite/execute! db))))

(let [[config-file infile] *command-line-args*
      {:keys [db labels] :as config} (json/parse-string (slurp config-file) true)]
  (create-schema db)
  (update-labels db labels)
  (doseq [{:keys [data label-answers uri]}
          (-> infile
              io/reader
              (json/parsed-seq true))
          :let [data-id (update-data db data uri)]]
    (update-answers config data-id label-answers)))
