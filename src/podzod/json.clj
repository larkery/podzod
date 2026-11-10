(ns podzod.json
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(defn response
  ([body] (response 200 body))
  ([status body]
   {:status status
    :headers {"Content-Type" "application/json"}
    :body (json/write-str body :escape-slash false)}))

(defn read-body
  "Parses a JSON request body with keyword keys; nil when empty. Uses the body
  captured by http/wrap-raw-body, so it works whatever the Content-Type says."
  [req]
  (let [s (or (:raw-body req) (some-> (:body req) slurp))]
    (when-not (str/blank? s)
      (json/read-str s :key-fn keyword))))

(defn write [x] (json/write-str x :escape-slash false))
