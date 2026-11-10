(ns podzod.oneoff-test
  (:require [clojure.test :refer [deftest is]]
            [podzod.oneoff :as o]))

(deftest link-kinds
  (is (= {:kind :apple :collection "1200361736" :track "1000650000000"}
         (o/classify-link "https://podcasts.apple.com/gb/podcast/the-daily/id1200361736?i=1000650000000")))
  (is (= :apple-show (:kind (o/classify-link "https://podcasts.apple.com/gb/podcast/the-daily/id1200361736"))))
  (is (= :spotify (:kind (o/classify-link "https://open.spotify.com/episode/abc"))))
  (is (= :audio (:kind (o/classify-link "https://cdn.example.com/ep/42.mp3?source=share"))))
  (is (= :page (:kind (o/classify-link "https://pca.st/episode/abcd"))))
  (is (= :unknown (:kind (o/classify-link "https://example.com/feed"))))
  (is (= :invalid (:kind (o/classify-link "not a url")))))
