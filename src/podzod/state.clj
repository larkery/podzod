(ns podzod.state
  "Derives listening state from the database and builds selection input."
  (:require [clojure.string :as str]
            [podzod.config :as config]
            [podzod.db :as db]
            [podzod.gpodder :as gpodder]
            [podzod.select :as select])
  (:import (java.time Instant LocalDateTime OffsetDateTime ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def lyrion-device "lyrion")

(defn parse-ts
  "gpodder action timestamp (usually \"2026-11-01T10:00:00\", UTC) -> unix seconds."
  [s]
  (when s
    (or (try (.getEpochSecond (Instant/parse s)) (catch Exception _ nil))
        (try (.toEpochSecond (OffsetDateTime/parse s)) (catch Exception _ nil))
        (try (.toEpochSecond (LocalDateTime/parse s) ZoneOffset/UTC) (catch Exception _ nil)))))

(defn format-ts [epoch]
  (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss")
           (LocalDateTime/ofEpochSecond epoch 0 ZoneOffset/UTC)))

(defn finished? [{:keys [position total]}]
  (boolean (and position total (pos? total)
                (or (>= position (- total 60)) (>= (/ position total) 0.95)))))

(defn play-states
  "episode url -> {:position :total :finished? :at} from the latest play action."
  [ds]
  (reduce (fn [m {:keys [episode position total timestamp received_ts] :as row}]
            (let [at (or (parse-ts timestamp) received_ts)
                  prev (m episode)]
              (if (and prev (> (:at prev) at))
                m
                (assoc m episode {:position position :total total :at at
                                  :finished? (finished? row) :podcast (:podcast row)}))))
          {}
          (db/query ds ["SELECT episode, podcast, position, total, timestamp, received_ts
                         FROM episode_actions WHERE action = 'play' AND episode IS NOT NULL ORDER BY id"])))

(defn- last-heard
  "feed key -> unix seconds of the most recent play or pick."
  [ds states]
  (let [picks (db/query ds ["SELECT feed_key, max(ts) AS ts FROM picks GROUP BY feed_key"])]
    (merge-with max
                (reduce (fn [m {:keys [podcast at]}]
                          (if podcast (update m podcast (fnil max 0) at) m))
                        {} (vals states))
                (into {} (map (juxt :feed_key :ts)) picks))))

(defn- row->episode [r]
  {:url (:url r) :guid (:guid r) :title (:title r) :pub-ts (:pub_ts r)
   :duration-s (:duration_s r) :season (:season r) :episode-no (:episode_no r)})

(defn annotate
  "Adds per-episode state (position, finished, skipped, recently picked)."
  [ep {:keys [states skipped recent]}]
  (let [st (states (:url ep))]
    (cond-> (assoc ep
                   :skipped? (contains? skipped (:url ep))
                   :recent? (contains? recent (:url ep)))
      st (assoc :position (if (:finished? st) nil (:position st))
                :finished? (or (:finished? ep) (:finished? st)))
      (and st (nil? (:duration-s ep)) (pos? (or (:total st) 0))) (assoc :duration-s (:total st)))))

(defn lookups [{:keys [ds cfg]} now]
  (let [hours (get-in cfg [:defaults :repick-hours] 12)]
    {:states (play-states ds)
     :skipped (set (map :episode_url (db/query ds ["SELECT episode_url FROM skipped"])))
     :recent (set (map :episode_url (db/query ds ["SELECT episode_url FROM picks WHERE ts >= ?"
                                                  (- now (* hours 3600))])))}))

(defn podcast-feeds
  "Subscribed RSS feeds with their episodes, as select/feed maps."
  [{:keys [ds cfg]} lk]
  (let [feed-rows (into {} (map (juxt :url identity)) (db/query ds ["SELECT * FROM feeds"]))
        eps-by-feed (group-by :feed_url (db/query ds ["SELECT * FROM episodes"]))]
    (for [url (gpodder/current-subscriptions ds)
          :let [override (config/feed-config cfg url)
                row (feed-rows url)
                episodes (mapv #(annotate (row->episode %) lk) (eps-by-feed url))]
          :when (not (:exclude override))]
      {:key url
       :title (or (:title override) (:title row) url)
       :override override
       :itunes-type (:itunes_type row)
       :news-category? (= 1 (:is_news_hint row))
       :weight (:weight override)
       :max-age-days (or (:max-age-days override) (get-in cfg [:defaults :news-max-age-days]))
       :episodes episodes})))

(defn finish-feeds
  "Classifies feeds and attaches last-heard."
  [feeds heard]
  (for [f feeds
        :let [[kind why] (or (some-> (:kind f) (vector "fixed")) (select/classify f))]]
    (assoc f :kind kind :why why :last-heard (heard (:key f)))))

(defn starred
  "Starred episodes (including one-offs) with details, as select episodes."
  [{:keys [ds]} lk extra-episodes]
  (let [rows (db/query ds ["SELECT s.episode_url, s.feed_key, s.ts,
                                   coalesce(e.title, o.title) AS title, e.pub_ts,
                                   coalesce(e.duration_s, o.duration_s) AS duration_s,
                                   e.guid, e.season, e.episode_no
                            FROM starred s
                            LEFT JOIN episodes e ON e.url = s.episode_url
                            LEFT JOIN oneoffs o ON o.episode_url = s.episode_url
                            GROUP BY s.episode_url"])]
    (for [r rows
          :let [extra (extra-episodes (:episode_url r))]]
      (annotate (merge {:url (:episode_url r) :title (:title r) :pub-ts (:pub_ts r)
                        :duration-s (:duration_s r) :guid (:guid r)}
                       extra
                       {:feed-key (:feed_key r) :starred-ts (:ts r)})
                lk))))

(defn context
  "Builds the input for select/pick. `extra-feeds` are already-shaped feeds
  from other sources (BBC) whose episodes carry their own :finished?."
  [app {:keys [max-s now extra-feeds]}]
  (let [now (or now (db/now))
        lk (lookups app now)
        extra (for [f extra-feeds
                    :let [override (config/feed-config (:cfg app) (:key f))]
                    :when (not (:exclude override))]
                (merge f
                       {:override override
                        :weight (:weight override)
                        :max-age-days (or (:max-age-days override)
                                          (get-in app [:cfg :defaults :news-max-age-days]))
                        :kind (when-not (or (contains? override :serial) (contains? override :news))
                                (:kind f))}
                       {:episodes (mapv #(annotate % lk) (:episodes f))}))
        feeds (concat (podcast-feeds app lk) extra)
        by-url (into {} (for [f feeds e (:episodes f)] [(:url e) e]))
        heard (last-heard (:ds app) (:states lk))]
    {:feeds (finish-feeds feeds heard)
     :starred (starred app lk #(some-> (by-url %) (select-keys [:title :pub-ts :duration-s :finished? :bbc])))
     :max-s max-s
     :now now}))

;; Writes

(defn record-play!
  "Writes a gpodder play action as the lyrion device, so the phone sees it."
  [ds {:keys [podcast episode guid started position total]}]
  (gpodder/insert-action! ds {:podcast podcast :episode episode :guid guid :action "play"
                              :timestamp (format-ts (db/now))
                              :started (or started 0) :position (or position 0) :total total
                              :device lyrion-device}
                          (db/now)))

(defn episode-info
  "What we know about an episode URL: {:url :feed-url :title :guid :duration-s} or nil."
  [ds url]
  (or (some-> (db/query-one ds ["SELECT e.*, f.title AS feed_title FROM episodes e
                                 LEFT JOIN feeds f ON f.url = e.feed_url WHERE e.url = ? LIMIT 1" url])
              (#(assoc (row->episode %) :feed-url (:feed_url %) :feed-title (:feed_title %))))
      (some-> (db/query-one ds ["SELECT * FROM oneoffs WHERE episode_url = ?" url])
              (#(hash-map :url url :feed-url (:feed_url %) :title (:title %) :duration-s (:duration_s %)
                          :oneoff? true)))))

(defn feed-key-for [ds url]
  (or (:feed-url (episode-info ds url))
      (when (str/starts-with? (str url) "sounds://") "bbc")
      "inbox"))

(defn skip! [ds url feed-key]
  (db/exec! ds ["INSERT INTO skipped (episode_url, feed_key, ts) VALUES (?, ?, ?)
                 ON CONFLICT (episode_url) DO NOTHING" url feed-key (db/now)]))

(defn unskip! [ds url] (db/exec! ds ["DELETE FROM skipped WHERE episode_url = ?" url]))

(defn star! [ds url feed-key note]
  (db/exec! ds ["INSERT INTO starred (episode_url, feed_key, note, ts) VALUES (?, ?, ?, ?)
                 ON CONFLICT (episode_url) DO NOTHING" url feed-key note (db/now)]))

(defn unstar! [ds url] (db/exec! ds ["DELETE FROM starred WHERE episode_url = ?" url]))

(defn record-pick! [ds feed-key url player]
  (db/exec! ds ["INSERT INTO picks (feed_key, episode_url, player, ts) VALUES (?, ?, ?, ?)"
                feed-key url player (db/now)]))
