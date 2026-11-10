(ns podzod.main
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as server]
            [podzod.config :as config]
            [podzod.control :as control]
            [podzod.db :as db]
            [podzod.feeds :as feeds]
            [podzod.gpodder :as gpodder]
            [podzod.http :as http]
            [podzod.plugin-repo :as plugin-repo]
            [podzod.state :as state])
  (:import (java.util.concurrent Executors TimeUnit)))

(defn- star-downloads!
  "Optionally treats an AntennaPod download as interest in the episode."
  [ds actions]
  (doseq [{:keys [action episode podcast]} actions
          :when (and episode (= "download" (some-> action str/lower-case)))]
    (state/star! ds episode podcast "downloaded on phone")))

(defn make-app [cfg]
  (let [ds (db/datasource (:db cfg))]
    (db/migrate! ds)
    {:cfg cfg
     :ds ds
     :session-id (atom (http/new-session-id))
     :on-subscribe #(feeds/refresh-all! ds %)
     :on-actions (when (get-in cfg [:defaults :download-means-star])
                   #(star-downloads! ds %))}))

(defn- start-refresher [{:keys [ds cfg]}]
  (let [minutes (get-in cfg [:defaults :refresh-minutes] 120)
        exec (Executors/newSingleThreadScheduledExecutor)]
    (.scheduleWithFixedDelay exec
                             (fn []
                               (try
                                 (let [urls (gpodder/current-subscriptions ds)]
                                   (log/info "refreshing" (count urls) "feeds")
                                   (feeds/refresh-all! ds urls))
                                 (catch Throwable e (log/error e "feed refresh failed"))))
                             0 minutes TimeUnit/MINUTES)
    exec))

(defn start [cfg]
  (when-not (:password cfg)
    (throw (ex-info "no password configured (:password-file or :password)" {})))
  (let [app (make-app cfg)
        stop (server/run-server (http/handler app
                                              (control/control-routes app)
                                              (plugin-repo/repo-routes app))
                                {:port (:port cfg) :ip (:bind cfg "0.0.0.0")
                                 :legacy-return-value? false})]
    (log/info "podzod listening on" (:port cfg))
    (assoc app :server stop :refresher (start-refresher app))))

(defn -main [& [config-path]]
  (start (config/load-config (or config-path (System/getenv "PODZOD_CONFIG")))))
