(ns podzod.control
  "Endpoints for Home Assistant and the LMS plugin."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compojure.core :refer [GET POST DELETE routes]]
            [podzod.bbc :as bbc]
            [podzod.db :as db]
            [podzod.duration :as duration]
            [podzod.json :as json]
            [podzod.lms :as lms]
            [podzod.oneoff :as oneoff]
            [podzod.select :as select]
            [podzod.state :as state])
  (:import (java.util Random)))

(defn- params
  "Query/form params merged with a JSON body, keyword keys."
  [req]
  (merge (update-keys (:params req) keyword)
         (when (some-> (:raw-body req) str/triml (str/starts-with? "{"))
           (let [b (json/read-body req)] (when (map? b) b)))))

(defn- error [e]
  (json/response (or (:status (ex-data e)) 500)
                 (merge {:error (.getMessage e)} (dissoc (ex-data e) :status))))

(defmacro ^:private handling [& body]
  `(try ~@body
        (catch clojure.lang.ExceptionInfo e# (error e#))
        (catch Exception e# (log/warn e# "request failed") (error e#))))

(defn- lms-cfg [app] (get-in app [:cfg :lms]))

(defn- episode-key [url] (if (bbc/bbc-url? url) (bbc/base-url url) url))

;; Selection

(defn build-context [app max-s]
  (state/context app {:max-s max-s :extra-feeds (bbc/feeds app)}))

(defn- display-title [feeds {:keys [feed-key episode]}]
  (let [feed-title (some #(when (= feed-key (:key %)) (:title %)) feeds)]
    (str/join ": " (remove str/blank? [feed-title (:title episode)]))))

(defn- play-url
  "BBC Sounds resumes via ?offset=; podcasts are seeked by the plugin."
  [{:keys [url position bbc]}]
  (if (and bbc position (pos? position)) (str url "?offset=" position) url))

(defn next! [app {:keys [player max mode]}]
  (let [max-s (duration/parse-max max)
        _ (when (and (not (str/blank? max)) (nil? max-s))
            (throw (ex-info "max should look like 30m, 1h30m or 45" {:status 400})))
        mode (keyword (or (not-empty mode) "next"))
        _ (when-not (#{:now :next :append} mode)
            (throw (ex-info "mode should be now, next or append" {:status 400})))
        ctx (build-context app max-s)
        choice (select/pick (assoc ctx :rng (Random.)))]
    (if-not choice
      (throw (ex-info "nothing suitable to play" {:status 404 :max max}))
      (let [lms (lms-cfg app)
            player (lms/resolve-player lms player)
            ep (:episode choice)
            title (display-title (:feeds ctx) choice)
            queued (lms/enqueue! lms player (play-url ep) title mode)]
        (state/record-pick! (:ds app) (:feed-key choice) (:url ep) player)
        (log/info "queued" title "(" (:reason choice) ")")
        (merge queued
               {:title title :url (:url ep) :feed (:feed-key choice) :tier (:tier choice)
                :reason (:reason choice) :duration-s (:duration-s ep) :resume-at (:position ep)})))))

(defn- feed-key-of [app url]
  (if (bbc/bbc-url? url)
    (or (some (fn [f] (when (some #(= (episode-key url) (:url %)) (:episodes f)) (:key f)))
              (bbc/feeds app))
        "bbc")
    (state/feed-key-for (:ds app) url)))

(defn skip! [app {:keys [player] :as p}]
  (let [lms (lms-cfg app)
        player (lms/resolve-player lms player)
        url (some-> (lms/current-url lms player) episode-key)]
    (when url
      (state/skip! (:ds app) url (feed-key-of app url))
      (log/info "skipped" url))
    (assoc (next! app (assoc p :player player :mode "now")) :skipped url)))

(defn star-current! [app {:keys [player note]}]
  (let [lms (lms-cfg app)
        url (some-> (lms/current-url lms (lms/resolve-player lms player)) episode-key)]
    (when-not url (throw (ex-info "nothing playing" {:status 404})))
    (state/star! (:ds app) url (feed-key-of app url) note)
    {:starred url}))

;; Play reporting from the plugin

(defonce ^:private started (atom {}))

(defn progress!
  "Records LMS playback of a known episode as a gpodder play action."
  [app {:keys [url position duration]}]
  (let [ds (:ds app)
        url (episode-key url)
        info (state/episode-info ds url)
        bbc? (bbc/bbc-url? url)
        position (some-> position double long)
        duration (some-> duration double long)]
    (when (and (or info bbc?) position)
      (let [total (or (:duration-s info) (when (and duration (pos? duration)) duration))
            start (get (swap! started update url #(or % position)) url)]
        (when (and (:oneoff? info) (nil? (:duration-s info)) total)
          (db/exec! ds ["UPDATE oneoffs SET duration_s = ? WHERE episode_url = ?" total url]))
        (state/record-play! ds {:podcast (if bbc? (feed-key-of app url) (or (:feed-url info) "inbox"))
                                :episode url :guid (:guid info)
                                :started start :position position :total total})
        (when (state/finished? {:position position :total total})
          (swap! started dissoc url))
        {:recorded true}))))

(defn resume-position [app url]
  (when-not (bbc/bbc-url? url)
    (let [st (get (state/play-states (:ds app)) url)]
      (when (and st (not (:finished? st)) (> (or (:position st) 0) 30))
        (:position st)))))

(defn mark! [app {:keys [url state]}]
  (let [ds (:ds app)
        url (episode-key url)
        info (state/episode-info ds url)
        total (or (:duration-s info) 1)
        base {:podcast (or (:feed-url info) (feed-key-of app url)) :episode url :guid (:guid info) :total total}]
    (case state
      "listened" (state/record-play! ds (assoc base :started 0 :position total))
      "unlistened" (do (state/record-play! ds (assoc base :started 0 :position 0))
                       (state/unskip! ds url))
      "skipped" (state/skip! ds url (feed-key-of app url))
      "unskipped" (state/unskip! ds url)
      "starred" (state/star! ds url (feed-key-of app url) nil)
      "unstarred" (state/unstar! ds url)
      (throw (ex-info "unknown state" {:status 400 :state state})))
    {:url url :state state}))

;; Menus for the plugin: {:title :items [{:name :type "menu" :menu {...}}
;;                                       {:name :type "action" :action {...}}
;;                                       {:name :type "text"}]}

(defn- marker [ep starred]
  (str (cond (:finished? ep) "✓ " (:skipped? ep) "⨯ " (pos? (or (:position ep) 0)) "◐ " :else "")
       (when (starred (:url ep)) "★ ")))

(defn fmt-duration [s]
  (when s (let [m (quot s 60)] (if (>= m 60) (format "%dh%02dm" (quot m 60) (rem m 60)) (str m "m")))))

(defn- episode-menu [ep starred]
  (let [url (:url ep)
        act (fn [name a] {:name name :type "action" :action (assoc a :url url)})]
    (remove nil?
            [(act "Play now" {:action "play" :mode "now"})
             (act "Play next" {:action "play" :mode "next"})
             (act "Add to end" {:action "play" :mode "append"})
             (if (starred url) (act "Unstar" {:action "mark" :state "unstarred"})
                 (act "Star (play first)" {:action "mark" :state "starred"}))
             (if (:finished? ep) (act "Mark unlistened" {:action "mark" :state "unlistened"})
                 (act "Mark listened" {:action "mark" :state "listened"}))
             (if (:skipped? ep) (act "Unskip" {:action "mark" :state "unskipped"})
                 (act "Skip (never pick)" {:action "mark" :state "skipped"}))])))

(defn- episode-item [ep starred]
  {:name (str (marker ep starred) (:title ep)
              (some->> (fmt-duration (:duration-s ep)) (str " · ")))
   :type "menu" :items (vec (episode-menu ep starred))})

(defn menu [app {:keys [menu feed]}]
  (let [starred-urls #(set (map :episode_url (db/query (:ds app) ["SELECT episode_url FROM starred"])))]
    (case (or (not-empty menu) "top")
      "top" {:title "Podzod"
             :items [{:name "Play something" :type "action" :action {:action "next"}}
                     {:name "Play something ≤ 30m" :type "action" :action {:action "next" :max "30m"}}
                     {:name "Play something ≤ 1h" :type "action" :action {:action "next" :max "1h"}}
                     {:name "Skip this (never pick again)" :type "action" :action {:action "skip"}}
                     {:name "Star what's playing" :type "action" :action {:action "star-current"}}
                     {:name "Starred & one-offs" :type "menu" :menu {:menu "inbox"}}
                     {:name "Subscriptions" :type "menu" :menu {:menu "subs"}}]}
      "inbox" (let [ctx (build-context app nil)
                    st (starred-urls)
                    eps (remove #(or (:finished? %) (:skipped? %)) (:starred ctx))]
                {:title "Starred & one-offs"
                 :items (if (seq eps)
                          (mapv #(episode-item % st) (sort-by :starred-ts eps))
                          [{:name "Nothing starred" :type "text"}])})
      "subs" (let [ctx (build-context app nil)]
               {:title "Subscriptions"
                :items (vec (for [f (sort-by (comp str/lower-case str :title) (:feeds ctx))
                                  :let [unheard (count (remove #(or (:finished? %) (:skipped? %)) (:episodes f)))]]
                              {:name (format "%s (%d) · %s" (:title f) unheard (name (:kind f)))
                               :type "menu" :menu {:menu "feed" :feed (:key f)}}))})
      "feed" (let [ctx (build-context app nil)
                   f (first (filter #(= feed (:key %)) (:feeds ctx)))
                   st (starred-urls)]
               {:title (:title f)
                :items (vec (concat
                             [{:name (str "Type: " (name (:kind f)) " (" (:why f) ")") :type "text"}]
                             (for [ep (take 100 (sort-by #(or (:pub-ts %) 0) > (:episodes f)))]
                               (episode-item ep st))))})
      (throw (ex-info "unknown menu" {:status 404})))))

(defn action!
  "Runs a menu action from the plugin and returns {:message ...}."
  [app {:keys [action url mode player] :as p}]
  (case action
    "next" (let [r (next! app p)] {:message (str "Playing next: " (:title r))})
    "skip" (let [r (skip! app p)] {:message (str "Skipped. Next: " (:title r))})
    "star-current" (do (star-current! app p) {:message "Starred"})
    "play" (let [lms (lms-cfg app)
                 player (lms/resolve-player lms player)
                 info (state/episode-info (:ds app) url)
                 title (str/join ": " (remove str/blank? [(:feed-title info) (:title info)]))]
             (lms/enqueue! lms player url title (keyword (or mode "now")))
             (state/record-pick! (:ds app) (feed-key-of app url) url player)
             {:message (str "Queued " (or (not-empty title) url))})
    "mark" (do (mark! app p) {:message (str "Marked " (:state p))})
    (throw (ex-info "unknown action" {:status 400}))))

(defn feeds-debug [app max]
  (let [ctx (build-context app (duration/parse-max max))]
    {:feeds (select/explain ctx)
     :starred (mapv #(select-keys % [:url :title :feed-key :finished? :skipped? :duration-s]) (:starred ctx))}))

(defn control-routes [app]
  (routes
   (POST "/next" req (handling (json/response (next! app (params req)))))
   (POST "/skip" req (handling (json/response (skip! app (params req)))))
   (POST "/star/current" req (handling (json/response (star-current! app (params req)))))
   (POST "/star" req (handling (let [{:keys [url note]} (params req)
                                     url (episode-key url)]
                                 (state/star! (:ds app) url (feed-key-of app url) note)
                                 (json/response {:starred url}))))
   (DELETE "/star" req (handling (state/unstar! (:ds app) (episode-key (:url (params req))))
                                 (json/response {:ok true})))
   (POST "/oneoff" req (handling (let [{:keys [url episode text]} (params req)
                                       ;; share targets often send "Title https://..." as text
                                       link (or url (re-find #"https?://\S+" (str text)))]
                                   (when-not link (throw (ex-info "no url given" {:status 400})))
                                   (json/response (oneoff/add! (:ds app) link episode)))))
   (GET "/feeds" req (handling (json/response (feeds-debug app (:max (params req))))))
   (POST "/lms/progress" req (handling (json/response (or (progress! app (params req)) {:recorded false}))))
   (GET "/lms/resume" req (handling (json/response {:position (resume-position app (:url (params req)))})))
   (GET "/lms/menu" req (handling (json/response (menu app (params req)))))
   (POST "/lms/action" req (handling (json/response (action! app (params req)))))))
