(ns podzod.plugin-repo-test
  (:require [clojure.test :refer [deftest is]]
            [podzod.http :as http]
            [podzod.plugin-repo :as repo]
            [podzod.test-util :as tu])
  (:import (java.io ByteArrayInputStream)
           (java.util.zip ZipInputStream)))

(defn- zip-entries [^bytes b]
  (with-open [z (ZipInputStream. (ByteArrayInputStream. b))]
    (loop [acc {}]
      (if-let [e (.getNextEntry z)]
        (recur (assoc acc (.getName e) (String. (.readAllBytes z) "UTF-8")))
        acc))))

(deftest repo
  (let [app (tu/test-app)
        h (http/handler app (repo/repo-routes app))
        req (fn [uri] (h {:request-method :get :uri uri :scheme :http :headers {"host" "pod.lan:8086"}}))
        xml (:body (req "/lms/repo.xml"))
        zip (.readAllBytes ^java.io.InputStream (:body (req "/lms/Podzod.zip")))
        files (zip-entries zip)]
    (is (re-find #"<url>http://pod.lan:8086/lms/Podzod.zip</url>" xml))
    (is (re-find (re-pattern (str "<sha>" (repo/sha1 zip) "</sha>")) xml))
    (is (re-find #"version=\"\d+\.\d+\.\d+\"" xml))
    (is (= (set repo/plugin-files) (set (keys files))))
    (is (re-find #"server   => 'http://pod.lan:8086'" (files "Plugin.pm")))
    (is (re-find #"user     => 'tom'" (files "Plugin.pm")))
    (is (= (seq zip) (seq (repo/build-zip {:server "http://pod.lan:8086" :user "tom"}))))))
