(ns podzod.ui-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [podzod.db :as db]
            [podzod.http :as http]
            [podzod.test-util :as tu]
            [podzod.ui :as ui]
            [ring.util.codec :as codec]))

(def feed "https://ex/feed.xml")

(defn- setup []
  (let [app (tu/test-app {:bbc {:enabled false} :lms {:url "http://127.0.0.1:1"}})
        ds (:ds app)]
    (db/exec! ds ["INSERT INTO subscription_changes (url, action, device, ts) VALUES (?, 'add', 'phone', 1)" feed])
    (db/exec! ds ["INSERT INTO feeds (url, title) VALUES (?, 'A Feed')" feed])
    (doseq [n [1 2]]
      (db/exec! ds ["INSERT INTO episodes (feed_url, url, title, pub_ts, duration_s) VALUES (?, ?, ?, ?, 1800)"
                    feed (str "https://ex/" n ".mp3") (str "Episode " n) (* n 86400)]))
    [app (http/handler app (ui/ui-routes app))]))

(defn- req [handler method uri & {:keys [form headers cookie]}]
  (handler (cond-> {:request-method method :uri uri
                    :headers (merge {"authorization" tu/auth-header} headers)}
             cookie (assoc-in [:headers "cookie"] cookie)
             form (-> (assoc-in [:headers "content-type"] "application/x-www-form-urlencoded")
                      (assoc :body (io/input-stream (.getBytes (codec/form-encode form) "UTF-8")))))))

(deftest pages
  (let [[_ h] (setup)]
    (doseq [uri ["/ui" "/ui/subs" "/ui/activity" "/ui/picker"]]
      (testing uri
        (let [r (req h :get uri)]
          (is (= 200 (:status r)))
          (is (re-find #"htmx-2\.0\.4" (:body r))))))
    (testing "feed page lists episodes newest first"
      (let [r (h {:request-method :get :uri "/ui/feed" :query-string (str "key=" (codec/url-encode feed))
                  :headers {"authorization" tu/auth-header}})]
        (is (= 200 (:status r)))
        (is (re-find #"(?s)Episode 2.*Episode 1" (:body r)))))
    (testing "vendored htmx is served"
      (is (= 200 (:status (req h :get "/ui/htmx-2.0.4.min.js")))))
    (testing "needs auth"
      (is (= 401 (:status (h {:request-method :get :uri "/ui" :headers {}})))))))

(deftest actions
  (let [[app h] (setup)
        starred #(set (map :episode_url (db/query (:ds app) ["SELECT episode_url FROM starred"])))]
    (testing "a plain form post stars, redirects back and flashes"
      (let [r (req h :post "/ui/do" :form {"do" "mark:starred" "url" "https://ex/1.mp3" "back" "/ui/subs"})]
        (is (= 303 (:status r)))
        (is (= "/ui/subs" (get-in r [:headers "Location"])))
        (is (= #{"https://ex/1.mp3"} (starred)))
        (is (some #(re-find #"^flash=Marked" %) (get-in r [:headers "Set-Cookie"])))
        (testing "the flash shows on the next page, then is cleared"
          (let [p (req h :get "/ui" :cookie "flash=Marked+starred")]
            (is (re-find #"Marked starred" (:body p)))
            (is (some #(re-find #"^flash=;" %) (get-in p [:headers "Set-Cookie"])))))))
    (testing "htmx row actions return just the row"
      (let [r (req h :post "/ui/do" :headers {"hx-request" "true"}
                   :form {"do" "mark:listened" "url" "https://ex/2.mp3" "fragment" "episode" "back" "/ui"})]
        (is (= 200 (:status r)))
        (is (re-find #"^<li><div>✓ Episode 2" (:body r)))
        (is (re-find #"hx-swap-oob" (:body r)))))
    (testing "failures are flashed as errors, not thrown"
      (let [r (req h :post "/ui/do" :form {"do" "next" "back" "/ui"})]
        (is (= 303 (:status r)))
        (is (some #(re-find #"^flash=%21" %) (get-in r [:headers "Set-Cookie"])))))
    (testing "back only goes to /ui pages"
      (is (= "/ui" (get-in (req h :post "/ui/do" :form {"do" "mark:starred" "url" "x" "back" "//evil.example"})
                           [:headers "Location"]))))
    (testing "cross-site posts are refused"
      (is (= 403 (:status (req h :post "/ui/do" :headers {"sec-fetch-site" "cross-site"}
                               :form {"do" "mark:unstarred" "url" "https://ex/1.mp3"}))))
      (is (= #{"https://ex/1.mp3" "x"} (starred))))))
