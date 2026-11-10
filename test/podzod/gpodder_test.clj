(ns podzod.gpodder-test
  (:require [clojure.test :refer [deftest is testing]]
            [podzod.http :as http]
            [podzod.test-util :as tu]))

(deftest auth
  (let [h (http/handler (tu/test-app))]
    (is (= 401 (first (tu/request h :get "/api/2/devices/tom.json" :auth nil))))
    (is (= 401 (first (tu/request h :get "/api/2/devices/tom.json" :auth "Basic dG9tOm5vcGU="))))
    (is (= 401 (first (tu/request h :get "/api/2/devices/someone.json"))))
    (is (= 200 (first (tu/request h :post "/api/2/auth/tom/login.json"))))))

(deftest login-cookie
  (let [app (tu/test-app)
        h (http/handler app)
        resp (h {:request-method :post :uri "/api/2/auth/tom/login.json" :headers {"authorization" tu/auth-header}})
        cookie (first (get-in resp [:headers "Set-Cookie"]))]
    (is (re-find #"sessionid=" cookie))
    (is (= 200 (:status (h {:request-method :get :uri "/api/2/devices/tom.json"
                            :headers {"cookie" (first (clojure.string/split cookie #";"))}}))))))

(deftest devices
  (let [h (http/handler (tu/test-app))]
    (is (= 200 (first (tu/request h :post "/api/2/devices/tom/phone.json"
                                  :body {:caption "Phone" :type "mobile"}))))
    (tu/request h :post "/api/2/devices/tom/phone.json" :body {:caption "My phone"})
    (is (= [{:id "phone" :caption "My phone" :type "mobile" :subscriptions 0}]
           (second (tu/request h :get "/api/2/devices/tom.json"))))))

(deftest subscriptions
  (let [subscribed (atom nil)
        h (http/handler (assoc (tu/test-app) :on-subscribe #(reset! subscribed %)))
        [s1 r1] (tu/request h :post "/api/2/subscriptions/tom/phone.json"
                            :body {:add ["http://a/feed" "http://b/feed"] :remove []})]
    (is (= 200 s1))
    (is (= [] (:update_urls r1)))
    (testing "full list from since=0"
      (let [[_ r] (tu/request h :get "/api/2/subscriptions/tom/phone.json" :query "since=0")]
        (is (= #{"http://a/feed" "http://b/feed"} (set (:add r))))
        (is (= [] (:remove r)))
        (is (pos? (:timestamp r)))))
    (testing "remove is reported"
      (tu/request h :post "/api/2/subscriptions/tom/phone.json" :body {:add [] :remove ["http://a/feed"]})
      (let [[_ r] (tu/request h :get "/api/2/subscriptions/tom/other.json" :query "since=0")]
        (is (= ["http://b/feed"] (:add r)))
        (is (= ["http://a/feed"] (:remove r)))))
    (testing "nothing after a future since"
      (let [[_ r] (tu/request h :get "/api/2/subscriptions/tom/phone.json"
                              :query (str "since=" (+ 100 (quot (System/currentTimeMillis) 1000))))]
        (is (= [] (:add r) (:remove r)))))
    (testing "add+remove of same url rejected"
      (is (= 400 (first (tu/request h :post "/api/2/subscriptions/tom/phone.json"
                                    :body {:add ["http://c"] :remove ["http://c"]}))))))
  )

(deftest episode-actions
  (let [h (http/handler (tu/test-app))
        action {:podcast "http://a/feed" :episode "http://a/1.mp3" :action "play"
                :timestamp "2026-11-01T10:00:00" :started 0 :position 120 :total 1800
                :device "phone" :guid "g1"}
        [s r] (tu/request h :post "/api/2/episodes/tom.json" :body [action])]
    (is (= 200 s))
    (is (= [] (:update_urls r)))
    (let [[_ r] (tu/request h :get "/api/2/episodes/tom.json" :query "since=0")]
      (is (= [action] (:actions r))))
    (let [[_ r] (tu/request h :get "/api/2/episodes/tom.json"
                            :query (str "since=" (+ 100 (quot (System/currentTimeMillis) 1000))))]
      (is (= [] (:actions r))))))

(deftest json-body-with-form-content-type
  (let [h (http/handler (tu/test-app))
        resp (h {:request-method :post :uri "/api/2/subscriptions/tom/phone.json"
                 :headers {"authorization" tu/auth-header
                           "content-type" "application/x-www-form-urlencoded"}
                 :body (java.io.ByteArrayInputStream. (.getBytes "{\"add\":[\"http://a\"],\"remove\":[]}"))})]
    (is (= 200 (:status resp)))
    (is (= ["http://a"] (:add (second (tu/request h :get "/api/2/subscriptions/tom/phone.json" :query "since=0")))))))
