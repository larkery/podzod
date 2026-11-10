(ns podzod.duration
  (:require [clojure.string :as str]))

(defn- clock->seconds
  "\"1:02:03\" or \"62:03\" -> seconds."
  [s]
  (when (re-matches #"\d+(:\d{1,2}){1,2}(\.\d+)?" s)
    (reduce (fn [acc part] (+ (* acc 60) (long (Double/parseDouble part)))) 0 (str/split s #":"))))

(defn- units->seconds
  "\"1h30m\", \"45m\", \"90s\" -> seconds."
  [s]
  (when-let [parts (re-matches #"(?i)(?:(\d+)h)?\s*(?:(\d+)m(?:in)?)?\s*(?:(\d+)s)?" s)]
    (let [[_ h m sec] parts]
      (when (or h m sec)
        (+ (* 3600 (parse-long (or h "0")))
           (* 60 (parse-long (or m "0")))
           (parse-long (or sec "0")))))))

(defn parse-max
  "Parses a ?max= value. A bare number means minutes."
  [s]
  (when-let [s (some-> s str/trim not-empty)]
    (or (some-> (re-matches #"\d+" s) parse-long (* 60))
        (clock->seconds s)
        (units->seconds s))))

(defn parse-itunes
  "Parses <itunes:duration>. A bare number means seconds."
  [s]
  (when-let [s (some-> s str/trim not-empty)]
    (or (when (re-matches #"\d+(\.\d+)?" s) (long (Double/parseDouble s)))
        (clock->seconds s)
        (units->seconds s))))
