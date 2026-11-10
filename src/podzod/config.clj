(ns podzod.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def defaults
  {:port 8086
   :db "podzod.db"
   :user "podzod"
   :lms {:url "http://localhost:9000"}
   :defaults {:news-max-age-days 7
              :refresh-minutes 120
              :repick-hours 12
              :download-means-star false}
   :feeds {}})

(defn- deep-merge [a b]
  (if (and (map? a) (map? b)) (merge-with deep-merge a b) b))

(defn load-config
  "Reads EDN config from `path`, merged over defaults. The password comes
  from :password-file (trimmed) unless :password is given directly."
  [path]
  (let [cfg (deep-merge defaults (when path (edn/read-string (slurp (io/file path)))))]
    (cond-> cfg
      (:password-file cfg) (assoc :password (str/trim (slurp (:password-file cfg)))))))

(defn feed-config
  "Hand-set overrides for a feed key (a feed URL or bbc:<pid>)."
  [cfg feed-key]
  (get-in cfg [:feeds feed-key]))
