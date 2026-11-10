(ns podzod.gpodder
  "Just enough of the gpodder.net v2 API for AntennaPod."
  (:require [clojure.data.json :as data.json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [podzod.db :as db]
            [podzod.json :as json]))

(defn- since-param [req]
  (or (some-> (get-in req [:query-params "since"]) parse-long) 0))

;; Devices

(defn current-subscriptions
  "URLs whose most recent subscription change is an add."
  [ds]
  (mapv :url (db/query ds ["SELECT c.url FROM subscription_changes c
                            WHERE c.id = (SELECT max(id) FROM subscription_changes WHERE url = c.url)
                              AND c.action = 'add'
                            GROUP BY c.url ORDER BY c.url"])))

(defn list-devices [{:keys [ds]} _req]
  (let [n (count (current-subscriptions ds))]
    (json/response
     (mapv (fn [d] {:id (:id d) :caption (:caption d) :type (:type d) :subscriptions n})
           (db/query ds ["SELECT * FROM devices ORDER BY id"])))))

(defn update-device [{:keys [ds]} req device]
  (let [{:keys [caption type]} (json/read-body req)]
    (db/exec! ds ["INSERT INTO devices (id, caption, type, updated_at) VALUES (?, ?, ?, ?)
                   ON CONFLICT (id) DO UPDATE SET
                     caption = coalesce(excluded.caption, caption),
                     type = coalesce(excluded.type, type),
                     updated_at = excluded.updated_at"
                  device caption type (db/now)])
    (json/response {})))

;; Subscriptions. Treated as belonging to the user rather than the device, so
;; every device converges on the same list.

(defn subscription-changes
  "Latest change per URL among changes at or after `since`."
  [ds since]
  (let [rows (db/query ds ["SELECT c.url, c.action FROM subscription_changes c
                            WHERE c.ts >= ?
                              AND c.id = (SELECT max(id) FROM subscription_changes WHERE url = c.url)"
                           since])]
    {:add (into [] (comp (filter #(= "add" (:action %))) (map :url)) rows)
     :remove (into [] (comp (filter #(= "remove" (:action %))) (map :url)) rows)}))

(defn get-subscriptions [{:keys [ds]} req _device]
  (let [now (db/now)]
    (json/response (assoc (subscription-changes ds (since-param req)) :timestamp now))))

(defn record-subscription-changes!
  "Records adds and removes; returns the URLs newly added."
  [ds device adds removes]
  (let [now (db/now)
        subscribed (set (current-subscriptions ds))]
    (doseq [url removes]
      (db/exec! ds ["INSERT INTO subscription_changes (url, action, device, ts) VALUES (?, 'remove', ?, ?)"
                    url device now]))
    (doseq [url adds]
      (db/exec! ds ["INSERT INTO subscription_changes (url, action, device, ts) VALUES (?, 'add', ?, ?)"
                    url device now]))
    (remove subscribed adds)))

(defn post-subscriptions [{:keys [ds on-subscribe]} req device]
  (let [{:keys [add remove]} (json/read-body req)
        add (vec (distinct add))
        remove (vec (distinct remove))]
    (if (some (set add) remove)
      (json/response 400 {:error "same URL in add and remove"})
      (let [new-urls (record-subscription-changes! ds device add remove)]
        (log/info "subscriptions from" device "add" (count add) "remove" (count remove))
        (when (and on-subscribe (seq new-urls))
          (future (try (on-subscribe new-urls)
                       (catch Exception e (log/warn e "refresh after subscribe failed")))))
        (json/response {:timestamp (db/now) :update_urls []})))))

;; Episode actions, stored as-is.

(defn insert-action!
  "Stores one episode action map (keyword keys) and returns nil."
  [ds a received]
  (db/exec! ds ["INSERT INTO episode_actions
                 (podcast, episode, guid, action, timestamp, started, position, total, device, received_ts, raw)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                (:podcast a) (:episode a) (:guid a) (some-> (:action a) str/lower-case)
                (:timestamp a) (:started a) (:position a) (:total a) (:device a)
                received (json/write a)]))

(defn get-episodes [{:keys [ds]} req]
  (let [now (db/now)
        rows (db/query ds ["SELECT raw FROM episode_actions
                            WHERE received_ts >= ? AND coalesce(episode, '') NOT LIKE 'sounds://%'
                            ORDER BY id"
                           (since-param req)])]
    (json/response {:actions (mapv #(data.json/read-str (:raw %)) rows)
                    :timestamp now})))

(defn post-episodes [{:keys [ds on-actions]} req]
  (let [actions (json/read-body req)
        now (db/now)]
    (if-not (sequential? actions)
      (json/response 400 {:error "expected a list of actions"})
      (do
        (doseq [a actions] (insert-action! ds a now))
        (log/info "stored" (count actions) "episode actions")
        (when on-actions (on-actions actions))
        (json/response {:timestamp now :update_urls []})))))
