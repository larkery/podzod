(ns podzod.test-util
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [podzod.config :as config]
            [podzod.main :as main])
  (:import (java.io File)
           (java.util Base64)))

(defn temp-db []
  (let [f (File/createTempFile "podzod" ".db")]
    (.deleteOnExit f)
    (.getPath f)))

(defn test-app
  ([] (test-app {}))
  ([extra]
   (main/make-app (merge config/defaults {:db (temp-db) :user "tom" :password "pw"} extra))))

(def auth-header
  (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes "tom:pw"))))

(defn request
  "Calls a ring handler with an optional JSON body; returns [status parsed-body]."
  [handler method uri & {:keys [body query auth] :or {auth auth-header}}]
  (let [resp (handler (cond-> {:request-method method :uri uri :headers {}}
                        auth (assoc-in [:headers "authorization"] auth)
                        query (assoc :query-string query)
                        body (assoc :body (io/input-stream (.getBytes (json/write-str body) "UTF-8")))))
        b (:body resp)]
    [(:status resp)
     (cond (string? b) (try (json/read-str b :key-fn keyword) (catch Exception _ b))
           :else b)]))
