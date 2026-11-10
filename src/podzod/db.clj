(ns podzod.db
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def opts {:builder-fn rs/as-unqualified-lower-maps})

(defn datasource [path]
  (jdbc/get-datasource {:dbtype "sqlite" :dbname path
                        :busy_timeout "5000" :journal_mode "WAL"}))

(defn migrate! [ds]
  (doseq [stmt (str/split (slurp (io/resource "schema.sql")) #";\s*\n")
          :when (not (str/blank? stmt))]
    (jdbc/execute! ds [stmt])))

(defn query [ds sqlvec] (jdbc/execute! ds sqlvec opts))
(defn query-one [ds sqlvec] (jdbc/execute-one! ds sqlvec opts))
(defn exec! [ds sqlvec] (jdbc/execute! ds sqlvec))

(defn now [] (quot (System/currentTimeMillis) 1000))
