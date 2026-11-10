(ns podzod.duration-test
  (:require [clojure.test :refer [deftest are]]
            [podzod.duration :as d]))

(deftest max-param
  (are [s secs] (= secs (d/parse-max s))
    "30m" 1800
    "30" 1800
    "1h" 3600
    "1h30m" 5400
    "90min" 5400
    "45s" 45
    "0:45:00" 2700
    "" nil
    nil nil
    "soon" nil))

(deftest itunes-duration
  (are [s secs] (= secs (d/parse-itunes s))
    "3723" 3723
    "1:02:03" 3723
    "62:03" 3723
    "3723.5" 3723
    " 00:30:00 " 1800
    "" nil))
