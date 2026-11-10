(ns podzod.select
  "Choosing the next episode. Pure: everything comes in as data.

  A feed is {:key :title :kind :weight :max-age-days :last-heard :episodes}
  and an episode is {:url :title :pub-ts :duration-s :season :episode-no
                     :position :finished? :skipped? :recent?}."
  (:import (java.util Random)))

(def day 86400)

;; Classification

(defn- median [xs]
  (when (seq xs)
    (let [v (vec (sort xs))] (nth v (quot (count v) 2)))))

(defn- mostly-numbered? [episodes]
  (and (>= (count episodes) 3)
       (>= (count (filter :episode-no episodes)) (* 0.8 (count episodes)))))

(defn- frequent? [episodes]
  (let [ts (->> episodes (keep :pub-ts) (sort >) (take 10))]
    (when (>= (count ts) 4)
      (<= (median (map - ts (rest ts))) (* 1.5 day)))))

(defn classify
  "Returns [kind reason] where kind is :serial, :news or :episodic.
  Hand-set :serial/:news overrides (true or false) beat feed metadata, which
  beats heuristics."
  [{:keys [override itunes-type news-category? episodes]}]
  (let [serial (:serial override)
        news (:news override)]
    (cond
      (true? serial) [:serial "set by hand"]
      (true? news) [:news "set by hand"]
      (and (nil? serial) (= "serial" itunes-type)) [:serial "itunes:type serial"]
      (and (nil? serial) (nil? itunes-type) (mostly-numbered? episodes)) [:serial "numbered episodes"]
      (false? news) [:episodic "news disabled by hand"]
      news-category? [:news "News category"]
      (frequent? episodes) [:news "publishes most days"]
      :else [:episodic "default"])))

;; Per-episode tests

(defn remaining [{:keys [duration-s position]}]
  (when duration-s (max 0 (- duration-s (or position 0)))))

(defn fits? [max-s ep]
  (or (nil? max-s)
      (when-let [r (remaining ep)] (<= r max-s))))

(defn- unheard? [ep] (not (or (:finished? ep) (:skipped? ep))))
(defn- playable? [ep] (and (unheard? ep) (not (:recent? ep))))

;; Randomness

(defn weighted-pick
  "Picks from [[item weight] ...] in proportion to weight."
  [^Random rng pairs]
  (let [pairs (filter (comp pos? second) pairs)
        total (reduce + (map second pairs))]
    (when (pos? total)
      (loop [r (* total (.nextDouble rng)) [[item w] & more] pairs]
        (if (or (< r w) (empty? more)) item (recur (- r w) more))))))

;; Per-feed candidates: each feed offers at most one episode, so a prolific
;; feed gets the same chance as a monthly one.

(defn serial-order [episodes]
  (if (mostly-numbered? episodes)
    (sort-by (juxt #(or (:season %) 0) #(or (:episode-no %) Long/MAX_VALUE) #(or (:pub-ts %) 0)) episodes)
    (sort-by #(or (:pub-ts %) 0) episodes)))

(defmulti candidate (fn [feed _ctx] (:kind feed)))

(defmethod candidate :serial [{:keys [episodes]} {:keys [max-s]}]
  ;; Never jump ahead: the next unheard episode or nothing.
  (let [ep (first (filter unheard? (serial-order episodes)))]
    (when (and ep (not (:recent? ep)) (fits? max-s ep)) ep)))

(defmethod candidate :news [{:keys [episodes max-age-days]} {:keys [max-s now]}]
  (let [cutoff (- now (* (or max-age-days 7) day))]
    (->> episodes
         (filter #(and (:pub-ts %) (>= (:pub-ts %) cutoff) (playable? %) (fits? max-s %)))
         (sort-by :pub-ts >)
         first)))

(defmethod candidate :default [{:keys [episodes]} {:keys [max-s rng]}]
  (->> episodes
       (filter #(and (playable? %) (fits? max-s %)))
       (sort-by #(or (:pub-ts %) 0) >)
       (take 20)
       (map-indexed (fn [i ep] [ep (/ 1.0 (inc i))]))
       (weighted-pick rng)))

(defn staleness
  "1 for a feed heard just now, rising to 2 after a fortnight (or never)."
  [last-heard now]
  (if last-heard
    (+ 1.0 (/ (min (max 0 (- now last-heard)) (* 14 day)) (* 14.0 day)))
    2.0))

(defn feed-weight [{:keys [weight last-heard]} now]
  (* (or weight 1.0) (staleness last-heard now)))

;; Top level

(defn- pick-starred
  "Strict priority: the oldest-starred playable episode that fits, with a
  random choice among those starred on the same day."
  [starred {:keys [max-s ^Random rng]}]
  (let [ok (filter #(and (playable? %) (fits? max-s %)) starred)]
    (when (seq ok)
      (let [oldest-day (apply min (map #(quot (:starred-ts %) day) ok))
            same-day (vec (filter #(= oldest-day (quot (:starred-ts %) day)) ok))]
        (nth same-day (.nextInt rng (count same-day)))))))

(defn pick
  "Returns {:episode :feed-key :tier :reason} or nil."
  [{:keys [feeds starred max-s now rng] :or {rng (Random.)} :as ctx}]
  (let [ctx (assoc ctx :rng rng)]
    (if-let [ep (pick-starred starred ctx)]
      {:episode ep :feed-key (:feed-key ep) :tier :starred :reason "starred"}
      (let [options (for [f feeds
                          :let [ep (candidate f ctx)]
                          :when ep]
                      [[f ep] (feed-weight f now)])]
        (when-let [[f ep] (weighted-pick rng options)]
          {:episode ep :feed-key (:key f) :tier (:kind f)
           :reason (format "%s feed, weight %.2f of %.2f"
                           (name (:kind f)) (feed-weight f now)
                           (reduce + (map second options)))})))))

(defn explain
  "Per-feed view for the /feeds debug endpoint."
  [{:keys [feeds now] :as ctx}]
  (let [ctx (assoc ctx :rng (Random. 0))]
    (for [f feeds]
      {:key (:key f) :title (:title f) :kind (:kind f) :why (:why f)
       :weight (feed-weight f now) :last-heard (:last-heard f)
       :episodes (count (:episodes f))
       :unheard (count (filter unheard? (:episodes f)))
       :candidate (some-> (candidate f ctx) (select-keys [:url :title :pub-ts :duration-s]))})))
