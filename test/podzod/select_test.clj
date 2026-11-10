(ns podzod.select-test
  (:require [clojure.test :refer [deftest is testing]]
            [podzod.select :as s])
  (:import (java.util Random)))

(def now 1794297600) ; 2026-11-10
(def day s/day)

(defn ep [n & {:as m}]
  (merge {:url (str "u" n) :title (str "ep" n) :pub-ts (- now (* (- 100 n) day))
          :duration-s 1800 :episode-no n :season 1}
         m))

(defn ctx [feeds & {:as m}] (merge {:feeds feeds :starred [] :now now :rng (Random. 1)} m))

(deftest classification
  (is (= :serial (first (s/classify {:override {:serial true} :itunes-type "episodic"}))))
  (is (= :serial (first (s/classify {:itunes-type "serial"}))))
  (is (= :episodic (first (s/classify {:override {:serial false} :itunes-type "serial"}))))
  (is (= :serial (first (s/classify {:episodes (map ep (range 1 6))}))))
  (is (= :episodic (first (s/classify {:itunes-type "episodic"
                                       :episodes (map #(ep % :pub-ts (- now (* 7 % day))) (range 1 6))}))))
  (is (= :news (first (s/classify {:itunes-type "episodic" :news-category? true}))))
  (is (= :news (first (s/classify {:itunes-type "episodic"
                                   :episodes (for [i (range 6)] {:pub-ts (- now (* i day))})}))))
  (is (= :episodic (first (s/classify {:override {:news false} :itunes-type "episodic" :news-category? true})))))

(deftest serial-never-skips-ahead
  (let [feed {:key "f" :kind :serial :episodes [(ep 1 :finished? true) (ep 2 :duration-s 7200) (ep 3)]}]
    (is (= "u2" (:url (s/candidate feed (ctx nil)))))
    (testing "next doesn't fit -> nothing, not episode 3"
      (is (nil? (s/candidate feed (ctx nil :max-s 1800)))))
    (testing "skipped counts as heard for sequence"
      (is (= "u3" (:url (s/candidate (update feed :episodes assoc 1 (ep 2 :skipped? true)) (ctx nil))))))
    (testing "recently picked -> nothing"
      (is (nil? (s/candidate (update feed :episodes assoc 1 (ep 2 :recent? true)) (ctx nil)))))))

(deftest serial-falls-back-to-date-order
  (let [eps [{:url "b" :pub-ts 200} {:url "a" :pub-ts 100} {:url "c" :pub-ts 300}]]
    (is (= ["a" "b" "c"] (map :url (s/serial-order eps))))))

(deftest news-shelf-life
  (let [feed {:key "n" :kind :news :max-age-days 7
              :episodes [(ep 1 :pub-ts (- now (* 8 day))) (ep 2 :pub-ts (- now (* 2 day)))
                         (ep 3 :pub-ts (- now day) :finished? true)]}]
    (is (= "u2" (:url (s/candidate feed (ctx nil)))))
    (is (nil? (s/candidate (update feed :episodes #(take 1 %)) (ctx nil))))))

(deftest max-duration
  (let [feed {:key "e" :kind :episodic
              :episodes [(ep 1 :duration-s 3600) (ep 2 :duration-s nil)
                         (ep 3 :duration-s 3600 :position 2400)]}]
    (testing "in-progress uses remaining time; unknown duration excluded"
      (is (= "u3" (:url (s/candidate feed (ctx nil :max-s 1800))))))))

(deftest skipped-never-chosen
  (let [feed {:key "e" :kind :episodic :episodes (map #(ep % :skipped? (odd? %)) (range 1 30))}]
    (dotimes [i 200]
      (is (even? (-> (s/candidate feed (ctx nil :rng (Random. i))) :url (subs 1) parse-long))))))

(deftest feed-weighting
  (testing "one candidate per feed, so a daily show doesn't swamp a weekly one"
    (let [daily {:key "daily" :kind :episodic :last-heard now :episodes (map ep (range 1 60))}
          weekly {:key "weekly" :kind :episodic :last-heard now :episodes [(ep 1)]}
          picks (frequencies (for [i (range 2000)]
                               (:feed-key (s/pick (ctx [daily weekly] :rng (Random. i))))))]
      (is (< 800 (picks "weekly") 1200))))
  (testing "staleness favours feeds not heard recently"
    (is (= 1.0 (s/staleness now now)))
    (is (= 2.0 (s/staleness nil now)))
    (is (= 2.0 (s/staleness (- now (* 30 day)) now)))
    (is (< 1.4 (s/staleness (- now (* 7 day)) now) 1.6)))
  (testing "weight 0 feeds never picked"
    (let [a {:key "a" :kind :episodic :weight 0 :episodes [(ep 1)]}
          b {:key "b" :kind :episodic :episodes [(ep 2)]}]
      (is (every? #{"b"} (for [i (range 50)] (:feed-key (s/pick (ctx [a b] :rng (Random. i))))))))))

(deftest starred-priority
  (let [feed {:key "f" :kind :episodic :episodes [(ep 1)]}
        st (fn [n ts & {:as m}] (merge (ep n :feed-key "x" :starred-ts ts) m))]
    (testing "starred beats normal pool"
      (is (= :starred (:tier (s/pick (ctx [feed] :starred [(st 50 now)]))))))
    (testing "oldest starred day first"
      (is (= "u51" (get-in (s/pick (ctx [feed] :starred [(st 50 now) (st 51 (- now (* 3 day)))]))
                           [:episode :url]))))
    (testing "falls through when none fit max"
      (is (= "u1" (get-in (s/pick (ctx [feed] :max-s 1800 :starred [(st 50 now :duration-s 7200)]))
                          [:episode :url]))))
    (testing "starred serial episode may be out of order"
      (let [serial {:key "s" :kind :serial :episodes [(ep 1) (ep 2)]}]
        (is (= "u2" (get-in (s/pick (ctx [serial] :starred [(st 2 now)])) [:episode :url])))))
    (testing "finished starred ignored"
      (is (= :episodic (:tier (s/pick (ctx [feed] :starred [(st 50 now :finished? true)]))))))))

(deftest nothing-available
  (is (nil? (s/pick (ctx [{:key "f" :kind :episodic :episodes [(ep 1 :finished? true)]}])))))
