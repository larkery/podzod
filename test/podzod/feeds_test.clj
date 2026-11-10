(ns podzod.feeds-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [podzod.db :as db]
            [podzod.feeds :as feeds]
            [podzod.test-util :as tu]))

(defn- fixture [n] (slurp (io/file "test/resources" n)))

(deftest parse-serial
  (let [f (feeds/parse-feed (fixture "serial.xml"))]
    (is (= "Serial Story" (:title f)))
    (is (= "serial" (:itunes-type f)))
    (is (false? (:news-category? f)))
    (is (= [{:url "https://ex/s/2.mp3" :guid "s2" :title "Chapter 2" :pub-ts 1793692800
             :duration-s 2400 :season 1 :episode-no 2}
            {:url "https://ex/s/1.mp3" :guid "s1" :title "Chapter 1" :pub-ts 1793088000
             :duration-s 1500 :season 1 :episode-no 1}]
           (:episodes f)))))

(deftest parse-news
  (let [f (feeds/parse-feed (fixture "news.xml"))]
    (is (:news-category? f))
    (is (nil? (:itunes-type f)))
    (is (= [1200 1200] (map :duration-s (:episodes f))))))

(deftest store
  (let [{:keys [ds]} (tu/test-app)]
    (feeds/store-feed! ds "https://ex/s" (feeds/parse-feed (fixture "serial.xml")) {:etag "x"})
    (feeds/store-feed! ds "https://ex/s" (feeds/parse-feed (fixture "serial.xml")) {:etag "y"})
    (is (= 2 (count (db/query ds ["SELECT * FROM episodes"]))))
    (is (= "y" (:etag (db/query-one ds ["SELECT etag FROM feeds"]))))))
