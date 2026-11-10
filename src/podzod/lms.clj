(ns podzod.lms
  "Minimal Lyrion Music Server JSON-RPC client."
  (:require [clojure.data.json :as json]
            [org.httpkit.client :as http]))

(defn request
  "Runs an LMS CLI command (a vector) for `player` (or \"-\"); returns :result."
  [{:keys [url user password]} player cmd]
  (let [{:keys [status body error]}
        @(http/post (str url "/jsonrpc.js")
                    (cond-> {:headers {"Content-Type" "application/json"}
                             :timeout 15000
                             :body (json/write-str {:id 1 :method "slim.request"
                                                    :params [(or player "-") (mapv str cmd)]})}
                      user (assoc :basic-auth [user password])))]
    (cond
      error (throw (ex-info (str "LMS unreachable: " error) {:cmd cmd} error))
      (not= 200 status) (throw (ex-info (str "LMS returned HTTP " status) {:cmd cmd :body body}))
      :else (:result (json/read-str body :key-fn keyword)))))

(defn players [lms]
  (:players_loop (request lms "-" ["players" 0 100])))

(defn resolve-player
  "The given player id, else the configured default, else the first player LMS knows."
  [lms player]
  (or (not-empty player)
      (:default-player lms)
      (:playerid (first (players lms)))
      (throw (ex-info "no LMS player available" {}))))

(defn status [lms player]
  (let [r (request lms player ["status" "-" 1 "tags:u"])]
    {:mode (:mode r)
     :index (some-> (:playlist_cur_index r) str parse-long)
     :tracks (or (:playlist_tracks r) 0)
     :time (:time r)
     :duration (:duration r)
     :current (first (:playlist_loop r))}))

(defn current-url [lms player]
  (get-in (status lms player) [:current :url]))

(defn enqueue!
  "Adds url to the player. mode :next inserts after the current track and starts
  it if nothing is playing; :now inserts and jumps to it; :append adds at the
  end and starts it if idle."
  [lms player url title mode]
  (let [{st :mode :keys [index tracks]} (status lms player)
        playing? (= "play" st)
        target (if (= mode :append) tracks (if index (inc index) 0))]
    (request lms player ["playlist" (if (= mode :append) "add" "insert") url (or title "")])
    (when (or (= mode :now) (not playing?) (zero? tracks))
      (request lms player ["playlist" "index" target]))
    {:player player :position target}))

(defn seek! [lms player secs]
  (request lms player ["time" secs]))
