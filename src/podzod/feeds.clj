(ns podzod.feeds
  "Fetching and parsing subscribed RSS feeds into the feeds/episodes tables."
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.httpkit.client :as http]
            [podzod.db :as db]
            [podzod.duration :as duration])
  (:import (java.time ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

;; data.xml 0.0.8 drops namespace prefixes, so <itunes:type> arrives as :type
;; and <itunes:title> as a second :title. The first match wins, which is the
;; plain RSS element in practice.

(defn- children [el tag] (filter #(and (map? %) (= tag (:tag %))) (:content el)))
(defn- child [el tag] (first (children el tag)))
(defn- text [el] (some->> (:content el) (filter string?) (apply str) str/trim not-empty))
(defn- child-text [el tag] (some-> (child el tag) text))

(def ^:private date-formats
  (mapv #(DateTimeFormatter/ofPattern % Locale/ENGLISH)
        ["EEE, d MMM yyyy HH:mm:ss Z" "EEE, d MMM yyyy HH:mm:ss z" "EEE, d MMM yyyy HH:mm Z"
         "d MMM yyyy HH:mm:ss Z" "EEE, d MMM yyyy HH:mm:ss zzzz"]))

(defn parse-date
  "RFC 822-ish pubDate -> unix seconds, or nil."
  [s]
  (when-let [s (some-> s str/trim not-empty)]
    (or (some (fn [^DateTimeFormatter f]
                (try (.toEpochSecond (ZonedDateTime/parse s f)) (catch Exception _ nil)))
              (cons DateTimeFormatter/RFC_1123_DATE_TIME date-formats))
        (try (.toEpochSecond (ZonedDateTime/parse s)) (catch Exception _ nil)))))

(defn- parse-int [s] (some->> s (re-find #"\d+") parse-long))

(defn- news-category? [channel]
  (some (fn [c] (some-> (or (get-in c [:attrs :text]) (text c)) str/lower-case (str/includes? "news")))
        (children channel :category)))

(defn parse-feed
  "Parses RSS text into {:title :itunes-type :news-category? :episodes [...]}.
  Items without an audio enclosure are dropped."
  [s]
  (let [root (xml/parse-str s :support-dtd false)
        channel (or (child root :channel) root)]
    {:title (child-text channel :title)
     :itunes-type (some-> (child-text channel :type) str/lower-case)
     :news-category? (boolean (news-category? channel))
     :episodes
     (vec (for [item (children channel :item)
                :let [url (get-in (child item :enclosure) [:attrs :url])]
                :when url
                :when (not= "trailer" (some-> (child-text item :episodeType) str/lower-case))]
            {:url url
             :guid (or (child-text item :guid) url)
             :title (child-text item :title)
             :pub-ts (parse-date (child-text item :pubDate))
             :duration-s (duration/parse-itunes (child-text item :duration))
             :season (parse-int (child-text item :season))
             :episode-no (parse-int (child-text item :episode))}))}))

(defn store-feed! [ds feed-url {:keys [title itunes-type news-category? episodes]} headers]
  (db/exec! ds ["INSERT INTO feeds (url, title, itunes_type, is_news_hint, etag, last_modified, fetched_at, error)
                 VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
                 ON CONFLICT (url) DO UPDATE SET title = excluded.title, itunes_type = excluded.itunes_type,
                   is_news_hint = excluded.is_news_hint, etag = excluded.etag,
                   last_modified = excluded.last_modified, fetched_at = excluded.fetched_at, error = NULL"
                feed-url title itunes-type (if news-category? 1 0)
                (:etag headers) (:last-modified headers) (db/now)])
  (doseq [{:keys [url guid title pub-ts duration-s season episode-no]} episodes]
    (db/exec! ds ["INSERT INTO episodes (feed_url, url, guid, title, pub_ts, duration_s, season, episode_no)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT (feed_url, url) DO UPDATE SET guid = excluded.guid, title = excluded.title,
                     pub_ts = excluded.pub_ts, season = excluded.season, episode_no = excluded.episode_no,
                     duration_s = coalesce(excluded.duration_s, duration_s)"
                  feed-url url guid title pub-ts duration-s season episode-no])))

(defn- mark-error! [ds feed-url msg]
  (db/exec! ds ["INSERT INTO feeds (url, fetched_at, error) VALUES (?, ?, ?)
                 ON CONFLICT (url) DO UPDATE SET fetched_at = excluded.fetched_at, error = excluded.error"
                feed-url (db/now) msg]))

(defn fetch-text
  "GETs a URL, returning {:status :body :headers} (body as string)."
  [url headers]
  @(http/get url {:headers (merge {"User-Agent" "podzod/1.0"} headers)
                  :timeout 30000 :as :text :follow-redirects true}))

(defn refresh-feed! [ds feed-url]
  (let [prev (db/query-one ds ["SELECT etag, last_modified FROM feeds WHERE url = ?" feed-url])
        {:keys [status body headers error]}
        (fetch-text feed-url (cond-> {}
                               (:etag prev) (assoc "If-None-Match" (:etag prev))
                               (:last_modified prev) (assoc "If-Modified-Since" (:last_modified prev))))]
    (cond
      error (do (log/warn "fetch failed" feed-url (str error)) (mark-error! ds feed-url (str error)))
      (= 304 status) (db/exec! ds ["UPDATE feeds SET fetched_at = ?, error = NULL WHERE url = ?" (db/now) feed-url])
      (not= 200 status) (do (log/warn "fetch" feed-url "status" status) (mark-error! ds feed-url (str "HTTP " status)))
      :else (try (store-feed! ds feed-url (parse-feed body) headers)
                 (catch Exception e
                   (log/warn "parse failed" feed-url (.getMessage e))
                   (mark-error! ds feed-url (str "parse: " (.getMessage e))))))))

(defn refresh-all! [ds urls]
  (doseq [u urls]
    (try (refresh-feed! ds u)
         (catch Exception e (log/warn e "refresh failed" u)))))
