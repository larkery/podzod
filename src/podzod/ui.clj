(ns podzod.ui
  "A small server-rendered web UI under /ui. Plain forms that post to /ui/do and
  redirect back; htmx (vendored) boosts navigation and swaps single episode
  rows so long feed pages keep their scroll position."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compojure.core :refer [GET POST routes]]
            [hiccup2.core :as h]
            [podzod.control :as control]
            [podzod.db :as db]
            [podzod.lms :as lms]
            [podzod.oneoff :as oneoff]
            [ring.util.codec :as codec])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)))

(def ^:private htmx "htmx-2.0.4.min.js")

(def ^:private css "
:root { color-scheme: light dark; --accent: light-dark(#1d5fd1, #7aa7ff);
        --muted: light-dark(#666, #999); --line: light-dark(#ddd, #333);
        --soft: light-dark(#f4f4f4, #1c1c1c); }
body { font: 16px/1.4 system-ui, sans-serif; margin: 0 auto; max-width: 52rem; padding: 0 16px 3rem;
       background: Canvas; color: CanvasText; }
nav { display: flex; gap: 1rem; flex-wrap: wrap; padding: .8rem 0; border-bottom: 1px solid var(--line); }
nav a { color: var(--accent); text-decoration: none; } nav a.on { font-weight: 600; color: CanvasText; }
h1 { font-size: 1.3rem; } h2 { font-size: 1.05rem; margin-top: 1.6rem; }
a { color: var(--accent); }
#flash:not(:empty) { margin: .8rem 0; padding: .5rem .8rem; border-radius: 6px; background: var(--soft); }
#flash.err { color: light-dark(#a00, #f77); }
ul.list { list-style: none; padding: 0; margin: 0; }
ul.list > li { padding: .6rem 0; border-bottom: 1px solid var(--line); }
.meta { color: var(--muted); font-size: .85rem; }
.acts form { display: flex; flex-wrap: wrap; gap: .3rem; margin-top: .35rem; }
button { font: inherit; font-size: .85rem; padding: .25rem .6rem; border: 1px solid var(--line);
         border-radius: 6px; background: var(--soft); color: CanvasText; cursor: pointer; }
button.primary { background: var(--accent); color: Canvas; border-color: var(--accent); }
.bar { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; }
select, input[type=text] { font: inherit; padding: .25rem .4rem; max-width: 100%; }
input[type=text] { flex: 1; min-width: 12rem; }
table { border-collapse: collapse; width: 100%; font-size: .9rem; }
td, th { text-align: left; padding: .35rem .4rem; border-bottom: 1px solid var(--line); vertical-align: top; }
.scroll { overflow-x: auto; }
.htmx-request { opacity: .5; }
")

;; Helpers

(def ^:private zone (ZoneId/systemDefault))

(defn- fmt-ts [pattern epoch]
  (when epoch (.format (.atZone (Instant/ofEpochSecond epoch) zone) (DateTimeFormatter/ofPattern pattern))))

(defn- href [path & {:as q}]
  (if (seq q) (str path "?" (codec/form-encode (update-keys q name))) path))

(defn- marker [ep starred?]
  (str (cond (:finished? ep) "✓ " (:skipped? ep) "⨯ " (pos? (or (:position ep) 0)) "◐ " :else "")
       (when starred? "★ ")))

(defn- page [req title active & body]
  (let [{:keys [flash flash-err]} (:ui req)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"}
     ;; the flash is shown once
     :cookies (when flash {"flash" {:value "" :path "/ui" :max-age 0}})
     :body (str "<!doctype html>"
                (h/html
                 [:html {:lang "en"}
                  [:head
                   [:meta {:charset "utf-8"}]
                   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                   [:title (str title " · podzod")]
                   [:style (h/raw css)]
                   [:script {:src (str "/ui/" htmx) :defer true}]]
                  [:body {:hx-boost "true"}
                   [:nav
                    (for [[k label path] [[:home "Home" "/ui"] [:subs "Subscriptions" "/ui/subs"]
                                          [:activity "Activity" "/ui/activity"] [:picker "Picker" "/ui/picker"]]]
                      [:a {:href path :class (when (= k active) "on")} label])]
                   [:div#flash {:class (when flash-err "err")} flash]
                   [:h1 title]
                   body]]))}))

(defn- action-form
  "A form posting to /ui/do; buttons carry the action as name=do."
  [req hidden & buttons]
  [:form {:method "post" :action "/ui/do"}
   [:input {:type "hidden" :name "back" :value (str (:uri req) (some->> (:query-string req) (str "?")))}]
   (for [[k v] hidden :when v] [:input {:type "hidden" :name (name k) :value v}])
   buttons])

(defn- btn [do label & [cls]] [:button {:name "do" :value do :class cls} label])

(defn- episode-row
  "An episode with its actions. With `swap?` the actions replace just this row."
  [req ep starred? & {:keys [swap? feed-title]}]
  (let [url (:url ep)]
    [:li
     [:div (marker ep starred?) (or (:title ep) url)]
     [:div.meta (str/join " · " (remove nil? [feed-title
                                             (fmt-ts "d MMM yyyy" (:pub-ts ep))
                                             (control/fmt-duration (:duration-s ep))
                                             (when (pos? (or (:position ep) 0))
                                               (str "at " (control/fmt-duration (:position ep))))]))]
     [:div.acts
      (cond-> (action-form req {:url url :fragment (when swap? "episode")}
                (btn "play:now" "Play now") (btn "play:next" "Play next") (btn "play:append" "Add to end")
                (if starred? (btn "mark:unstarred" "Unstar") (btn "mark:starred" "Star"))
                (if (:finished? ep) (btn "mark:unlistened" "Unlistened") (btn "mark:listened" "Listened"))
                (if (:skipped? ep) (btn "mark:unskipped" "Unskip") (btn "mark:skipped" "Skip")))
        swap? (update 1 assoc :hx-post "/ui/do" :hx-target "closest li" :hx-swap "outerHTML"))]]))

(defn- starred-set [{:keys [ds]}]
  (set (map :episode_url (db/query ds ["SELECT episode_url FROM starred"]))))

(defn- players [app]
  (try (lms/players (get-in app [:cfg :lms]))
       (catch Exception e (log/debug e "no LMS players") [])))

;; Pages

(defn- home [app req]
  (let [ctx (control/build-context app nil)
        feed-titles (into {} (map (juxt :key :title)) (:feeds ctx))
        inbox (sort-by :starred-ts (remove #(or (:finished? %) (:skipped? %)) (:starred ctx)))
        current (or (get-in req [:ui :player]) (get-in app [:cfg :lms :default-player]))
        picks (db/query (:ds app) ["SELECT p.episode_url, p.feed_key, p.ts,
                                     (SELECT title FROM episodes WHERE url = p.episode_url LIMIT 1) AS title,
                                     (SELECT title FROM oneoffs WHERE episode_url = p.episode_url) AS oneoff_title
                                    FROM picks p ORDER BY p.id DESC LIMIT 5"])]
    (page req "Home" :home
          (action-form req {}
            [:div.bar
             (btn "next" "Play something" "primary") (btn "next:30m" "≤ 30m") (btn "next:1h" "≤ 1h")
             (btn "skip" "Skip this") (btn "star-current" "Star what's playing")
             (let [ps (players app)]
               (when (seq ps)
                 [:select {:name "player" :aria-label "Player"}
                  (for [{:keys [playerid name]} ps]
                    [:option {:value playerid :selected (= playerid current)} (or name playerid)])]))])
          [:h2 "Add a one-off"]
          (action-form req {}
            [:div.bar
             [:input {:type "text" :name "text" :placeholder "Episode, Apple Podcasts or Pocket Casts link"
                      :aria-label "Link"}]
             (btn "oneoff" "Star it")])
          [:h2 "Starred & one-offs"]
          (if (seq inbox)
            [:ul.list (for [ep inbox]
                        (episode-row req ep true :feed-title (feed-titles (:feed-key ep))))]
            [:p.meta "Nothing starred."])
          [:h2 "Recently picked"]
          (if (seq picks)
            [:ul.list (for [{:keys [episode_url feed_key ts title oneoff_title]} picks]
                        [:li [:div (or title oneoff_title episode_url)]
                         [:div.meta (str/join " · " (remove nil? [(feed-titles feed_key) (fmt-ts "EEE d MMM HH:mm" ts)]))]])]
            [:p.meta "Nothing picked yet."]))))

(defn- subs-page [app req]
  (let [ctx (control/build-context app nil)]
    (page req "Subscriptions" :subs
          (if (seq (:feeds ctx))
            [:ul.list
             (for [f (sort-by (comp str/lower-case str :title) (:feeds ctx))
                   :let [unheard (count (remove #(or (:finished? %) (:skipped? %)) (:episodes f)))]]
               [:li [:a {:href (href "/ui/feed" :key (:key f))} (:title f)]
                [:div.meta (format "%d unheard · %s" unheard (name (:kind f)))]])]
            [:p.meta "No subscriptions yet. Add some in AntennaPod and sync."]))))

(defn- feed-page [app req key]
  (let [ctx (control/build-context app nil)
        f (first (filter #(= key (:key %)) (:feeds ctx)))
        st (starred-set app)]
    (if-not f
      {:status 404 :headers {"Content-Type" "text/plain"} :body "no such feed"}
      (page req (:title f) :subs
            [:p.meta (str (name (:kind f)) " (" (:why f) ")") " · "
             (if (str/starts-with? key "http") [:a {:href key} "feed"] key)]
            [:ul.list (for [ep (take 100 (sort-by #(or (:pub-ts %) 0) > (:episodes f)))]
                        (episode-row req ep (contains? st (:url ep)) :swap? true))]))))

(defn- activity-page [app req]
  (let [ds (:ds app)
        actions (db/query ds ["SELECT a.action, a.device, a.position, a.total, a.received_ts, a.episode,
                                (SELECT title FROM episodes WHERE url = a.episode LIMIT 1) AS title,
                                (SELECT title FROM oneoffs WHERE episode_url = a.episode) AS oneoff_title,
                                (SELECT title FROM feeds WHERE url = a.podcast) AS feed_title
                               FROM episode_actions a ORDER BY a.id DESC LIMIT 50"])
        devices (db/query ds ["SELECT * FROM devices ORDER BY updated_at DESC"])
        subs (db/query ds ["SELECT c.url, c.action, c.device, c.ts, f.title FROM subscription_changes c
                            LEFT JOIN feeds f ON f.url = c.url ORDER BY c.id DESC LIMIT 20"])]
    (page req "Activity" :activity
          [:h2 "Episode actions"]
          (if (seq actions)
            [:div.scroll
             [:table
              [:tr [:th "When"] [:th "Device"] [:th "Action"] [:th "Episode"]]
              (for [{:keys [action device position total received_ts episode title oneoff_title feed_title]} actions]
                [:tr [:td (fmt-ts "d MMM HH:mm" received_ts)] [:td device]
                 [:td action (when (and (= "play" action) position)
                               (str " " (control/fmt-duration position)
                                    (when total (str " / " (control/fmt-duration total)))))]
                 [:td (or title oneoff_title episode) (when feed_title [:div.meta feed_title])]])]]
            [:p.meta "No episode actions yet."])
          [:h2 "Subscription changes"]
          (if (seq subs)
            [:div.scroll
             [:table
              [:tr [:th "When"] [:th "Device"] [:th "Change"] [:th "Feed"]]
              (for [{:keys [url action device ts title]} subs]
                [:tr [:td (fmt-ts "d MMM HH:mm" ts)] [:td device] [:td action] [:td (or title url)]])]]
            [:p.meta "No subscription changes yet."])
          [:h2 "Devices"]
          [:ul.list (for [{:keys [id caption type updated_at]} devices]
                      [:li id [:div.meta (str/join " · " (remove str/blank? [caption type
                                                                           (fmt-ts "d MMM yyyy HH:mm" updated_at)]))]])])))

(defn- picker-page [app req]
  (let [max (get-in req [:params "max"])
        {:keys [feeds]} (control/feeds-debug app max)]
    (page req "Picker" :picker
          [:form.bar {:method "get" :action "/ui/picker"}
           [:label "Max length "
            [:select {:name "max"}
             (for [[v label] [["" "any"] ["30m" "30m"] ["45m" "45m"] ["1h" "1h"] ["2h" "2h"]]]
               [:option {:value v :selected (= v (str max))} label])]]
           [:button "Show"]]
          [:p.meta "Each feed offers at most one candidate; a feed is then chosen at random by weight."]
          [:div.scroll
           [:table
            [:tr [:th "Feed"] [:th "Type"] [:th "Weight"] [:th "Unheard"] [:th "Candidate"]]
            (for [f (sort-by (comp - :weight) feeds)]
              [:tr
               [:td (if (str/starts-with? (:key f) "bbc:") (:title f) [:a {:href (href "/ui/feed" :key (:key f))} (:title f)])]
               [:td (some-> (:kind f) name) [:div.meta (:why f)]]
               [:td (format "%.2f" (double (:weight f)))]
               [:td (:unheard f)]
               [:td (if-let [c (:candidate f)]
                      [:span (:title c) [:div.meta (control/fmt-duration (:duration-s c))]]
                      [:span.meta "none"])]])]])))

;; Actions

(defn- run-action!
  "Runs a `do` value from a form; returns the message to flash."
  [app {:strs [do url text player]}]
  (let [[verb arg] (str/split (str do) #":" 2)
        p {:player player :url url}]
    (case verb
      "next" (:message (control/action! app (assoc p :action "next" :max arg)))
      "skip" (:message (control/action! app (assoc p :action "skip")))
      "star-current" (:message (control/action! app (assoc p :action "star-current")))
      "play" (:message (control/action! app (assoc p :action "play" :mode arg)))
      "mark" (:message (control/action! app (assoc p :action "mark" :state arg)))
      "oneoff" (let [link (re-find #"https?://\S+" (str text))]
                 (when-not link (throw (ex-info "That doesn't contain a link" {})))
                 (str "Starred " (:title (oneoff/add! (:ds app) link nil))))
      (throw (ex-info "unknown action" {})))))

(defn- find-episode [app url]
  (let [ctx (control/build-context app nil)]
    (some #(when (= url (:url %)) %) (concat (mapcat :episodes (:feeds ctx)) (:starred ctx)))))

(defn- safe-back [back] (if (and back (re-matches #"/ui(/[^/].*|\?.*)?" back)) back "/ui"))

(defn- do! [app req]
  (let [params (:form-params req)
        player (not-empty (get params "player"))
        params (cond-> params (not player) (assoc "player" (get-in req [:ui :player])))
        [msg err?] (try [(run-action! app params) false]
                        (catch Exception e
                          (log/warn "ui action failed:" (.getMessage e))
                          [(.getMessage e) true]))
        cookies (cond-> {"flash" {:value (str (if err? "!" "") msg) :path "/ui"}}
                  player (assoc "player" {:value player :path "/ui" :max-age (* 365 24 3600)}))]
    (if (and (get-in req [:headers "hx-request"]) (= "episode" (get params "fragment")))
      (let [url (get params "url")
            ep (find-episode app url)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :cookies (assoc cookies "flash" {:value "" :path "/ui" :max-age 0})
         :body (str (h/html (if ep
                              (episode-row req ep (contains? (starred-set app) url) :swap? true)
                              [:li.meta "Gone"])
                            [:div#flash {:hx-swap-oob "true" :class (when err? "err")} msg]))})
      {:status 303
       :headers {"Location" (safe-back (get params "back"))}
       :cookies cookies})))

(defn- wrap-ui
  "Reads the flash and remembered player into :ui, and refuses cross-site posts
  (Basic auth credentials are sent along with those)."
  [handler]
  (fn [req]
    (if (and (= :post (:request-method req))
             (str/starts-with? (:uri req) "/ui")
             (= "cross-site" (get-in req [:headers "sec-fetch-site"])))
      {:status 403 :headers {"Content-Type" "text/plain"} :body "cross-site request refused"}
      (let [flash (not-empty (get-in req [:cookies "flash" :value]))
            err? (some-> flash (str/starts-with? "!"))]
        (handler (assoc req :ui {:flash (cond-> flash err? (subs 1))
                                 :flash-err err?
                                 :player (not-empty (get-in req [:cookies "player" :value]))}))))))

(defn ui-routes [app]
  (wrap-ui
   (routes
    (GET "/" [] {:status 302 :headers {"Location" "/ui"}})
    (GET "/ui" req (home app req))
    (GET "/ui/subs" req (subs-page app req))
    (GET "/ui/feed" req (feed-page app req (get-in req [:params "key"])))
    (GET "/ui/activity" req (activity-page app req))
    (GET "/ui/picker" req (picker-page app req))
    (POST "/ui/do" req (do! app req))
    (GET (str "/ui/" htmx) []
      {:status 200
       :headers {"Content-Type" "text/javascript" "Cache-Control" "public, max-age=31536000, immutable"}
       :body (slurp (io/resource (str "public/" htmx)))}))))
