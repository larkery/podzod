(ns podzod.bbc
  "BBC Sounds followed programmes, fetched through the Podzod LMS plugin so the
  BBC Sounds plugin's signed-in session is reused."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [podzod.lms :as lms])
  (:import (java.time Instant)))

(defn base-url
  "sounds://_<id>_<pid>?offset=N -> sounds://_<id>_<pid>, the episode's key."
  [url]
  (first (str/split (str url) #"\?" 2)))

(defn bbc-url? [url] (str/starts-with? (str url) "sounds://"))

(defn- pid-from-urn [urn] (last (str/split (str urn) #":")))

(defn- parse-instant [s]
  (try (.getEpochSecond (Instant/parse s)) (catch Exception _ nil)))

(defn item->episode [item]
  (let [item (or (:playable_item item) item)
        id (:id item)
        pid (pid-from-urn (:urn item))
        duration (get-in item [:duration :value])
        progress (get-in item [:progress :value])
        {:keys [primary secondary tertiary]} (:titles item)]
    (when (and id pid)
      {:url (str "sounds://_" id "_" pid)
       :title (str/join " " (remove str/blank? [secondary tertiary]))
       :programme (or (get-in item [:container :title]) primary)
       :programme-id (or (get-in item [:container :id]) primary)
       :pub-ts (some-> (get-in item [:release :date]) parse-instant)
       :duration-s duration
       :position progress
       :finished? (boolean (and progress duration (pos? duration)
                                (or (>= progress (- duration 60)) (>= (/ progress duration) 0.95))))
       :bbc true})))

(defn ->feeds
  "Groups BBC episodes into feed maps keyed bbc:<programme id>."
  [episodes]
  (for [[prog eps] (group-by :programme-id episodes)]
    {:key (str "bbc:" prog)
     :title (str "BBC: " (:programme (first eps)))
     :kind :episodic
     :episodes (vec eps)}))

(defn fetch-page [lms offset]
  (let [{:keys [json error]} (lms/request lms "-" ["podzod" "bbc" offset])]
    (when error (throw (ex-info (str "BBC via LMS: " error) {})))
    (some-> json (json/read-str :key-fn keyword))))

(defn fetch-episodes
  "Follows/playable, up to `pages` pages."
  [lms pages]
  (loop [offset 0 page 0 acc []]
    (let [{:keys [data limit total]} (fetch-page lms offset)
          acc (into acc (keep item->episode) data)
          next-offset (+ offset (or limit (count data)))]
      (if (and (seq data) (< (inc page) pages) total (< next-offset total))
        (recur next-offset (inc page) acc)
        acc))))

(def ^:private cache (atom nil))

(defn feeds
  "BBC feeds, cached for :bbc :cache-minutes (default 10). Returns [] when
  disabled or unavailable so a BBC problem never blocks /next."
  [{:keys [cfg]}]
  (let [{:keys [enabled cache-minutes pages] :or {enabled true cache-minutes 10 pages 2}} (:bbc cfg)
        now (System/currentTimeMillis)]
    (if-not enabled
      []
      (if-let [{:keys [at value]} @cache]
        (if (< (- now at) (* cache-minutes 60000))
          value
          (do (reset! cache nil) (feeds {:cfg cfg})))
        (try
          (let [v (vec (->feeds (fetch-episodes (:lms cfg) pages)))]
            (reset! cache {:at now :value v})
            v)
          (catch Exception e
            (log/warn "BBC Sounds unavailable:" (.getMessage e))
            (reset! cache {:at (- now (* (max 0 (dec cache-minutes)) 60000)) :value []})
            []))))))

(defn invalidate! [] (reset! cache nil))
