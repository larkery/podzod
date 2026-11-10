(ns podzod.oneoff
  "Turning a shared link into a single playable episode."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [podzod.db :as db]
            [podzod.feeds :as feeds]
            [podzod.state :as state])
  (:import (java.net URI)))

(def ^:private audio-ext #"(?i)\.(mp3|m4a|aac|ogg|oga|opus|mp4|wav|flac)(\?.*)?$")

(defn classify-link
  "Works out what kind of link this is, without fetching anything."
  [url]
  (let [host (some-> (try (.getHost (URI. url)) (catch Exception _ nil)) str/lower-case)]
    (cond
      (nil? host) {:kind :invalid}
      (str/includes? host "spotify.") {:kind :spotify}
      (str/ends-with? host "podcasts.apple.com")
      (let [[_ collection] (re-find #"/id(\d+)" url)
            [_ track] (re-find #"[?&]i=(\d+)" url)]
        (if track {:kind :apple :collection collection :track track} {:kind :apple-show}))
      (#{"pca.st" "pocketcasts.com" "play.pocketcasts.com"} host) {:kind :page}
      (re-find audio-ext (.getPath (URI. url))) {:kind :audio}
      :else {:kind :unknown})))

(defn- get-url [url & [opts]]
  @(http/get url (merge {:timeout 20000 :as :text :follow-redirects true
                         :headers {"User-Agent" "podzod/1.0"}} opts)))

(defn- match-episode
  "Finds an episode in a parsed feed by guid, exact url, or title substring."
  [episodes selector]
  (let [sel (some-> selector str/lower-case)]
    (or (first (filter #(or (= selector (:guid %)) (= selector (:url %))) episodes))
        (when sel (first (filter #(some-> (:title %) str/lower-case (str/includes? sel)) episodes))))))

(defn- from-feed-text [feed-url text selector]
  (let [{:keys [title episodes]} (feeds/parse-feed text)]
    (if-let [ep (and selector (match-episode episodes selector))]
      (assoc ep :feed-url feed-url :feed-title title)
      (throw (ex-info (if selector "no episode in that feed matches" "that's a feed; say which episode")
                      {:status 400
                       :latest (mapv #(select-keys % [:title :guid]) (take 10 (sort-by :pub-ts > episodes)))})))))

(defn- from-apple [{:keys [collection track]}]
  (let [{:keys [body]} (get-url (str "https://itunes.apple.com/lookup?id=" collection
                                     "&entity=podcastEpisode&limit=300"))
        results (:results (json/read-str body :key-fn keyword))
        ep (first (filter #(= track (str (:trackId %))) results))]
    (if-not (:episodeUrl ep)
      (throw (ex-info "Apple lookup didn't find that episode (it may be too old for the lookup API)"
                      {:status 404}))
      {:url (:episodeUrl ep) :title (:trackName ep) :guid (:episodeGuid ep)
       :feed-url (:feedUrl ep) :feed-title (:collectionName ep)
       :duration-s (some-> (:trackTimeMillis ep) (quot 1000))})))

(defn- from-page
  "Looks for an audio URL in an HTML page (og:audio, <audio>, or any audio link)."
  [url]
  (let [{:keys [body status]} (get-url url)
        audio (or (second (re-find #"(?i)<meta[^>]+property=[\"']og:audio(?::url)?[\"'][^>]+content=[\"']([^\"']+)" (str body)))
                  (second (re-find #"(?i)<audio[^>]+src=[\"']([^\"']+)" (str body)))
                  (second (re-find #"(?i)[\"'](https?://[^\"'\s]+\.(?:mp3|m4a)(?:\?[^\"'\s]*)?)[\"']" (str body))))
        title (second (re-find #"(?i)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)" (str body)))]
    (if audio
      {:url (str/replace audio "&amp;" "&") :title title}
      (throw (ex-info (str "couldn't find audio on that page (HTTP " status ")") {:status 422})))))

(defn resolve-link
  "Returns {:url :title ...} for a link, or throws ex-info with :status."
  [url selector]
  (let [{:keys [kind] :as c} (classify-link url)]
    (case kind
      :invalid (throw (ex-info "not a URL" {:status 400}))
      :spotify (throw (ex-info "Spotify links can't be played outside Spotify" {:status 422}))
      :apple-show (throw (ex-info "that's a show, not an episode; share the episode link" {:status 400}))
      :apple (from-apple c)
      :audio {:url url}
      :page (from-page url)
      :unknown (let [{:keys [body status headers]} (get-url url)
                     ctype (str (:content-type headers))]
                 (cond
                   (not= 200 status) (throw (ex-info (str "HTTP " status " fetching link") {:status 422}))
                   (str/starts-with? ctype "audio/") {:url url}
                   (re-find #"<(rss|feed)[\s>]" (subs (str body) 0 (min 2000 (count (str body)))))
                   (from-feed-text url body selector)
                   :else (from-page url))))))

(defn- join-title [feed-title title]
  (some->> [feed-title title] (remove nil?) seq (str/join ": ")))

(defn- known-episode
  "An episode of a subscribed feed with this URL, if we have one."
  [ds url]
  (let [info (state/episode-info ds url)]
    (when (and info (not (:oneoff? info))) info)))

(defn- star-known! [ds {:keys [url feed-url feed-title title duration-s]} link]
  (state/star! ds url feed-url (str "shared " link))
  {:url url :title (or (join-title feed-title title) url) :duration-s duration-s
   :feed feed-url :subscribed true})

(defn add!
  "Stars a shared episode so it's played first. Episodes of subscribed feeds
  are just starred; anything else is stored as a one-off in the inbox."
  [ds link selector]
  (if-let [known (known-episode ds link)]
    (star-known! ds known link)
    (let [{:keys [url title feed-url feed-title duration-s]} (resolve-link link selector)]
      (if-let [known (known-episode ds url)]
        (star-known! ds known link)
        (let [title (or (join-title feed-title title) url)]
          (db/exec! ds ["INSERT INTO oneoffs (episode_url, title, feed_url, duration_s, source_link, added_ts)
                         VALUES (?, ?, ?, ?, ?, ?)
                         ON CONFLICT (episode_url) DO UPDATE SET title = excluded.title,
                           duration_s = coalesce(excluded.duration_s, duration_s)"
                        url title feed-url duration-s link (db/now)])
          (state/star! ds url "inbox" (str "one-off from " link))
          {:url url :title title :duration-s duration-s :feed "inbox" :subscribed false})))))
