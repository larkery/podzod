(ns podzod.http
  (:require [clojure.string :as str]
            [compojure.core :refer [GET POST DELETE routes context]]
            [clojure.tools.logging :as log]
            [compojure.route :as route]
            [podzod.gpodder :as gpodder]
            [podzod.json :as json]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cookies :refer [wrap-cookies]])
  (:import (java.security MessageDigest SecureRandom)
           (java.util Base64 HexFormat)))

(defn- constant-eq? [^String a ^String b]
  (and a b (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8"))))

(defn- basic-credentials [req]
  (when-let [h (get-in req [:headers "authorization"])]
    (when-let [[_ enc] (re-matches #"(?i)basic\s+(\S+)" h)]
      (let [s (String. (.decode (Base64/getDecoder) ^String enc) "UTF-8")
            i (str/index-of s ":")]
        (when i [(subs s 0 i) (subs s (inc i))])))))

(defn new-session-id []
  (let [b (byte-array 24)]
    (.nextBytes (SecureRandom.) b)
    (.formatHex (HexFormat/of) b)))

(defn authenticated? [{:keys [cfg session-id]} req]
  (or (when-let [[u p] (basic-credentials req)]
        (and (constant-eq? u (:user cfg)) (constant-eq? p (:password cfg))))
      (constant-eq? (get-in req [:cookies "sessionid" :value]) @session-id)))

(def ^:private public-paths #{"/lms/repo.xml" "/lms/Podzod.zip" "/health"})

(defn wrap-auth [handler app]
  (fn [req]
    (cond
      (contains? public-paths (:uri req)) (handler req)
      (authenticated? app req) (handler req)
      :else {:status 401
             :headers {"WWW-Authenticate" "Basic realm=\"podzod\""
                       "Content-Type" "text/plain"}
             :body "unauthorised"})))

(defn- user-ok? [{:keys [cfg]} user] (= user (:user cfg)))

(defmacro ^:private as-user
  "Runs body only when the {user} path segment is the configured user."
  [app user & body]
  `(if (user-ok? ~app ~user) (do ~@body) (json/response 401 {:error "wrong user"})))

(defn gpodder-routes [app]
  (context "/api/2" []
    (POST "/auth/:user/login.json" [user]
      (as-user app user
        {:status 200
         :headers {"Content-Type" "application/json"}
         :cookies {"sessionid" {:value @(:session-id app) :path "/" :http-only true}}
         :body "{}"}))
    (POST "/auth/:user/logout.json" [user] (as-user app user (json/response {})))
    (GET "/devices/:user.json" [user :as req] (as-user app user (gpodder/list-devices app req)))
    (POST "/devices/:user/:device.json" [user device :as req]
      (as-user app user (gpodder/update-device app req device)))
    (GET "/subscriptions/:user/:device.json" [user device :as req]
      (as-user app user (gpodder/get-subscriptions app req device)))
    (POST "/subscriptions/:user/:device.json" [user device :as req]
      (as-user app user (gpodder/post-subscriptions app req device)))
    (GET "/episodes/:user.json" [user :as req] (as-user app user (gpodder/get-episodes app req)))
    (POST "/episodes/:user.json" [user :as req] (as-user app user (gpodder/post-episodes app req)))))

(defn wrap-raw-body
  "Keeps the request body as :raw-body before wrap-params can consume it as a
  form: clients don't always send JSON with a JSON Content-Type."
  [handler]
  (fn [req]
    (if-let [b (:body req)]
      (let [bytes (.readAllBytes ^java.io.InputStream b)]
        (handler (assoc req
                        :raw-body (String. bytes "UTF-8")
                        :body (java.io.ByteArrayInputStream. bytes))))
      (handler req))))

(defn wrap-log
  "Logs every request but /health with its status, so failed auth and unknown
  paths show up too."
  [handler]
  (fn [req]
    (let [resp (try (handler req)
                    (catch Throwable e
                      (log/error e (:request-method req) (:uri req) "failed")
                      {:status 500 :headers {"Content-Type" "text/plain"} :body "error"}))]
      (when-not (= "/health" (:uri req))
        (log/info (str/upper-case (name (:request-method req))) (:uri req) "->" (:status resp)
                  (cond-> ""
                    (= 401 (:status resp))
                    (str (if (get-in req [:headers "authorization"]) "(bad credentials)" "(no credentials)")))))
      resp)))

(defn handler
  "Builds the ring handler. `extra` is a seq of further route handlers."
  [app & extra]
  (-> (apply routes
             (GET "/health" [] (json/response {:ok true}))
             (gpodder-routes app)
             (concat extra [(route/not-found "not found")]))
      (wrap-auth app)
      wrap-cookies
      wrap-params
      wrap-raw-body
      wrap-log))
