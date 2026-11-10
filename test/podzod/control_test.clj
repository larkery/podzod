(ns podzod.control-test
  "End to end: real HTTP server, a fake LMS and a fake feed host."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [org.httpkit.client :as client]
            [org.httpkit.server :as server]
            [podzod.bbc :as bbc]
            [podzod.config :as config]
            [podzod.main :as main]
            [podzod.test-util :as tu]))

(def lms-commands (atom []))
(def lms-current (atom nil))

(def bbc-page
  {:data [{:id "m001" :urn "urn:bbc:radio:episode:m001"
           :titles {:primary "In Our Time" :secondary "The Tudors"}
           :duration {:value 2700} :progress {:value 600}
           :release {:date "2026-11-09T09:00:00Z"}
           :container {:id "b006qykl" :title "In Our Time"}}]
   :offset 0 :limit 30 :total 1})

(defn- fake-lms [req]
  (let [{[player cmd] :params} (json/read-str (slurp (:body req)) :key-fn keyword)]
    (swap! lms-commands conj [player cmd])
    {:status 200 :headers {"Content-Type" "application/json"}
     :body (json/write-str
            {:result (case (first cmd)
                       "status" (if-let [u @lms-current]
                                  {:mode "play" :playlist_cur_index "0" :playlist_tracks 1
                                   :playlist_loop [{:url u :title "x"}]}
                                  {:mode "stop" :playlist_tracks 0})
                       "players" {:players_loop [{:playerid "aa:bb"}]}
                       "podzod" {:json (json/write-str bbc-page)}
                       {})})}))

(defn- fake-feeds [req]
  (case (:uri req)
    "/serial" {:status 200 :body (slurp (io/file "test/resources/serial.xml"))}
    "/ep.mp3" {:status 200 :headers {"Content-Type" "audio/mpeg"} :body "x"}
    {:status 404}))

(def ^:dynamic *base* nil)
(def ^:dynamic *feeds* nil)

(use-fixtures :each
  (fn [t]
    (reset! lms-commands []) (reset! lms-current nil) (bbc/invalidate!)
    (let [lms (server/run-server fake-lms {:port 0 :legacy-return-value? false})
          fh (server/run-server fake-feeds {:port 0 :legacy-return-value? false})
          cfg (merge config/defaults
                     {:db (tu/temp-db) :user "tom" :password "pw" :port 0
                      :lms {:url (str "http://localhost:" (server/server-port lms))}
                      :defaults (assoc (:defaults config/defaults) :refresh-minutes 600)})
          app (with-redefs [podzod.main/start-refresher (constantly nil)] (main/start cfg))]
      (try
        (binding [*base* (str "http://localhost:" (server/server-port (:server app)))
                  *feeds* (str "http://localhost:" (server/server-port fh))]
          (t))
        (finally
          (server/server-stop! (:server app))
          (server/server-stop! lms)
          (server/server-stop! fh))))))

(defn- call [method path & [body]]
  (let [{:keys [status body]} @(client/request
                                (cond-> {:method method :url (str *base* path) :basic-auth ["tom" "pw"]}
                                  body (assoc :body (json/write-str body)
                                              :headers {"Content-Type" "application/json"})))]
    [status (json/read-str body :key-fn keyword)]))

(defn- lms-cmds [] (map second @lms-commands))

(deftest next-and-skip
  (let [feed (str *feeds* "/serial")]
    (call :post "/api/2/subscriptions/tom/phone.json" {:add [feed] :remove []})
    (Thread/sleep 1000)
    (testing "serial feed: chapter 1 first; BBC also a candidate"
      (let [[s r] (call :get "/feeds")]
        (is (= 200 s))
        (is (= #{feed "bbc:b006qykl"} (set (map :key (:feeds r)))))
        (is (= "https://ex/s/1.mp3" (:url (:candidate (first (filter #(= feed (:key %)) (:feeds r)))))))))
    (testing "max too small for anything"
      (is (= 404 (first (call :post "/next?max=10m")))))
    (testing "/next queues on the first player"
      (let [[s r] (call :post "/next")]
        (is (= 200 s))
        (is (= "aa:bb" (:player r)))
        (is (some #(= "insert" (second %)) (lms-cmds)))
        (when (= "bbc:b006qykl" (:feed r))
          (testing "BBC resumes via ?offset"
            (is (some #(= ["playlist" "insert" "sounds://_m001_m001?offset=600"] (take 3 %))
                      (lms-cmds)))))))
    (testing "/skip marks what's playing and plays something else now"
      (reset! lms-current "https://ex/s/1.mp3")
      (reset! lms-commands [])
      (let [[s r] (call :post "/skip")
            [_ fs] (call :get "/feeds")
            candidate (:url (:candidate (first (filter #(= feed (:key %)) (:feeds fs)))))]
        (is (= 200 s))
        (is (= "https://ex/s/1.mp3" (:skipped r)))
        (is (some #(= ["playlist" "index" "1"] %) (lms-cmds)))
        (testing "skipped chapter 1 -> serial moves on to chapter 2 (unless just picked)"
          (if (= "https://ex/s/2.mp3" (:url r))
            (is (nil? candidate))
            (is (= "https://ex/s/2.mp3" candidate))))))))

(deftest progress-sync-to-phone
  (let [feed (str *feeds* "/serial")]
    (call :post "/api/2/subscriptions/tom/phone.json" {:add [feed] :remove []})
    (Thread/sleep 1000)
    (call :post "/lms/progress" {:player "aa:bb" :url "https://ex/s/1.mp3" :position 300.4 :duration 1500})
    (is (= 300 (:position (second (call :get "/lms/resume?url=https://ex/s/1.mp3")))))
    (let [[_ r] (call :get "/api/2/episodes/tom.json?since=0")
          a (last (:actions r))]
      (is (= {:podcast feed :episode "https://ex/s/1.mp3" :action "play" :device "lyrion"
              :started 300 :position 300 :total 1500}
             (select-keys a [:podcast :episode :action :device :started :position :total]))))
    (testing "BBC progress stays local"
      (call :post "/lms/progress" {:url "sounds://_m001_m001?offset=600" :position 700 :duration 2700})
      (is (not-any? #(= "sounds://_m001_m001" (:episode %))
                    (:actions (second (call :get "/api/2/episodes/tom.json?since=0"))))))
    (testing "mark listened from LMS"
      (call :post "/lms/action" {:action "mark" :state "listened" :url "https://ex/s/1.mp3"})
      (let [a (last (:actions (second (call :get "/api/2/episodes/tom.json?since=0"))))]
        (is (= [1500 1500] [(:position a) (:total a)])))
      (is (nil? (:position (second (call :get "/lms/resume?url=https://ex/s/1.mp3"))))))))

(deftest stars-and-oneoffs
  (let [url (str *feeds* "/ep.mp3")
        [s r] (call :post "/oneoff" {:text (str "Have a listen " url)})]
    (is (= 200 s))
    (is (= url (:url r)))
    (testing "one-off is starred and wins strictly"
      (is (= url (:url (second (call :post "/next"))))))
    (testing "once picked it isn't picked again straight away"
      (is (not= url (:url (second (call :post "/next"))))))
    (testing "inbox menu lists it"
      (let [[_ r] (call :get "/lms/menu?menu=inbox")]
        (is (= 1 (count (:items r))))))))

(deftest menus
  (call :post "/api/2/subscriptions/tom/phone.json" {:add [(str *feeds* "/serial")] :remove []})
  (Thread/sleep 1000)
  (let [[_ top] (call :get "/lms/menu")
        [_ subs] (call :get "/lms/menu?menu=subs")
        feed-menu (:menu (first (filter #(re-find #"Serial" (:name %)) (:items subs))))
        [_ f] (call :get (str "/lms/menu?menu=feed&feed=" (java.net.URLEncoder/encode (:feed feed-menu) "UTF-8")))]
    (is (= "Podzod" (:title top)))
    (is (= 2 (count (:items subs))))
    (is (= "Serial Story" (:title f)))
    (is (re-find #"serial" (:name (first (:items f)))))
    (is (= 3 (count (:items f))))))
