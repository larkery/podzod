(ns podzod.plugin-repo
  "Serves the LMS plugin as a one-plugin LMS extension repository, so LMS can
  install and update it from Settings > Manage Plugins > Additional Repositories."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [compojure.core :refer [GET routes]])
  (:import (java.io ByteArrayOutputStream)
           (java.security MessageDigest)
           (java.util HexFormat)
           (java.util.zip ZipEntry ZipOutputStream)))

;; Explicit list, because listing a directory inside the uberjar is awkward.
(def plugin-files
  ["install.xml" "Plugin.pm" "Settings.pm" "strings.txt"
   "HTML/EN/plugins/Podzod/settings/basic.html"])

(defn- resource-text [f] (slurp (io/resource (str "lms-plugin/Podzod/" f))))

(defn plugin-version []
  (second (re-find #"<version>([^<]+)</version>" (resource-text "install.xml"))))

(defn- fill-in [f text {:keys [server user]}]
  (if (= f "Plugin.pm")
    (-> text
        (str/replace "__PODZOD_SERVER__" server)
        (str/replace "__PODZOD_USER__" user))
    text))

(defn build-zip
  "Plugin files at the zip root (LMS extracts relative to install.xml).
  Fixed entry times keep the bytes, and so the sha1, stable."
  ^bytes [vars]
  (let [out (ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [f plugin-files]
        (.putNextEntry zip (doto (ZipEntry. ^String f) (.setTime 315532800000)))
        (.write zip (.getBytes ^String (fill-in f (resource-text f) vars) "UTF-8"))
        (.closeEntry zip)))
    (.toByteArray out)))

(defn sha1 [^bytes b]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-1") b)))

(defn base-url
  "Where the client reached us, honouring a reverse proxy. The host ends up in
  Perl source and XML, so anything odd falls back to localhost."
  [req]
  (let [proto (or (get-in req [:headers "x-forwarded-proto"]) (name (:scheme req :http)))
        host (or (get-in req [:headers "x-forwarded-host"]) (get-in req [:headers "host"]))]
    (str (if (#{"http" "https"} proto) proto "http")
         "://"
         (if (and host (re-matches #"[A-Za-z0-9.\-]+(:\d{1,5})?|\[[0-9a-fA-F:]+\](:\d{1,5})?" host))
           host
           "localhost"))))

(def ^:private zips (atom {}))

(defn zip-for [vars]
  (or (@zips vars)
      (let [b (build-zip vars)]
        (swap! zips #(assoc (if (> (count %) 16) {} %) vars b))
        b)))

(defn repo-xml [base sha]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<extensions>\n"
       "  <details><title lang=\"EN\">podzod</title></details>\n"
       "  <plugins>\n"
       "    <plugin name=\"Podzod\" version=\"" (plugin-version) "\" minTarget=\"8.0\" maxTarget=\"*\">\n"
       "      <title lang=\"EN\">Podzod</title>\n"
       "      <desc lang=\"EN\">Podcasts synced with AntennaPod via podzod, and play something sensible next.</desc>\n"
       "      <creator>podzod</creator>\n"
       "      <category>musicservices</category>\n"
       "      <url>" base "/lms/Podzod.zip</url>\n"
       "      <sha>" sha "</sha>\n"
       "    </plugin>\n"
       "  </plugins>\n"
       "</extensions>\n"))

(defn repo-routes [{:keys [cfg]}]
  (let [vars (fn [req] {:server (base-url req) :user (:user cfg)})]
    (routes
     (GET "/lms/repo.xml" req
       {:status 200
        :headers {"Content-Type" "application/xml"}
        :body (repo-xml (base-url req) (sha1 (zip-for (vars req))))})
     (GET "/lms/Podzod.zip" req
       {:status 200
        :headers {"Content-Type" "application/zip"}
        :body (java.io.ByteArrayInputStream. (zip-for (vars req)))}))))
