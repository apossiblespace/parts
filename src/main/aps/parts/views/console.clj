(ns aps.parts.views.console
  "HTML of the Operator console page. `aps.parts.console` calls it with
   figures from `aps.parts.stats`.

   The page shows counts and timestamps only. It never shows Map titles or
   other Map content. See 'Operator console' in `CONTEXT.md`."
  (:require
   [aps.parts.stats :as stats]
   [aps.parts.views.partials :as partials]
   [hiccup2.core :refer [html raw]])
  (:import
   (java.time Duration LocalDate OffsetDateTime ZoneOffset)))

;;; Formatting

(defn- ago
  "Returns how long before `now` the moment `t` was, as \"3 h ago\", or
   \"never\" when `t` is nil."
  [^OffsetDateTime now ^OffsetDateTime t]
  (if-not t
    "never"
    (let [minutes (.toMinutes (Duration/between t now))]
      (cond
        (< minutes 60)        (str minutes " min ago")
        (< minutes (* 24 60)) (str (quot minutes 60) " h ago")
        :else                 (str (quot minutes (* 24 60)) " d ago")))))

(defn- billing-badge
  [{:keys [status]}]
  (case status
    :paid       [:span.badge.badge-sm.badge-soft.badge-success "paid"]
    :overdue    [:span.badge.badge-sm.badge-soft.badge-warning "overdue"]
    :never-paid [:span.badge.badge-sm.badge-ghost "never paid"]))

(defn- day-strip
  "A row of one cell for each day of the activity window, oldest first.
   The cell of a day in `active-days` is filled."
  [^LocalDate today active-days]
  [:span {:class "inline-grid grid-cols-[repeat(30,5px)] gap-px align-middle"
          :title "Active days in the last 30 days. The oldest day is on the left."}
   (for [offset (range (dec stats/activity-days) -1 -1)]
     [:i {:class (if (active-days (.minusDays today offset))
                   "h-3 rounded-[1px] bg-primary-content"
                   "h-3 rounded-[1px] bg-base-300")}])])

;;; Stats

(defn- tile
  [label value detail]
  [:div {:class "bg-base-100 px-3 py-2 flex flex-col min-w-0"}
   [:span {:class "text-xs font-semibold uppercase tracking-wide opacity-60"} label]
   [:span {:class "text-2xl font-semibold tabular-nums"} value]
   [:span {:class "text-xs opacity-60 tabular-nums"} detail]])

(defn- tiles
  [{:keys [users active billing founding_circle product_updates]}]
  (let [window (fn [k] (format "%.0f%% of users" (-> active k :pct)))]
    [:div {:class "grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-px bg-base-300 border border-base-300 rounded-box overflow-hidden"}
     (tile "Users" (:total users)
           (str founding_circle " founding · " (:pending_deletion users) " pending deletion"))
     (tile "Active 24 h" (-> active :last_24h :count) (window :last_24h))
     (tile "Active 7 d" (-> active :last_7d :count) (window :last_7d))
     (tile "Active 30 d" (-> active :last_30d :count) (window :last_30d))
     (tile "Paid" (:paid billing)
           (str (:overdue billing) " overdue · " (:never_paid billing) " never paid"))
     (tile "Product updates" (:subscribed product_updates)
           (str (:opted_out product_updates) " opted out"))]))

(defn- user-row
  [today now {:keys [display_name email created_at is_founding_circle pending_deletion
                     product_updates_opted_out billing last_active active_days counts]}]
  [:tr
   [:td
    [:div {:class "flex flex-col"}
     [:span display_name " "
      (when is_founding_circle
        [:span {:class "badge badge-xs badge-ghost" :title "Founding Circle"} "FC"])
      " "
      (when pending_deletion
        [:span {:class "badge badge-xs badge-soft badge-error"} "pending deletion"])
      " "
      (when product_updates_opted_out
        [:span {:class "badge badge-xs badge-outline opacity-60"
                :title "Opted out of product updates"}
         "no updates"])]
     [:span {:class "text-xs opacity-60"} email]]]
   [:td {:class "opacity-70"}
    (str (-> ^java.sql.Timestamp created_at .toInstant (.atOffset ZoneOffset/UTC) .toLocalDate))]
   [:td (billing-badge billing)]
   [:td (ago now last_active)]
   [:td {:class "text-right whitespace-nowrap"}
    (day-strip today active_days) " " [:b (count active_days)]]
   [:td {:class "text-right"} [:b (:maps counts)] " / " (:maps_edited counts)]
   [:td {:class "text-right"} [:b (:sessions counts)] " / " (:sessions_started counts)]
   [:td {:class "text-right"} (:parts counts)]
   [:td {:class "text-right"} (:relationships counts)]
   [:td {:class "text-right"} (:conversation_entries counts)]])

(defn- users-table
  [{:keys [users today now]}]
  [:div {:class "overflow-x-auto bg-base-100 border border-base-300 rounded-box"}
   [:table {:class "table table-sm tabular-nums whitespace-nowrap"}
    [:thead
     [:tr
      [:th "User"]
      [:th "Signed up"]
      [:th "Billing"]
      [:th "Last active"]
      [:th {:class "text-right"} "Active days, 30 d"]
      [:th {:class "text-right" :title "Current Maps / Maps changed in the last 30 days"}
       "Maps now / 30 d"]
      [:th {:class "text-right" :title "All Sessions / Sessions started in the last 30 days"}
       "Sessions all / 30 d"]
      [:th {:class "text-right" :title "Changes in the last 30 days"} "Parts Δ"]
      [:th {:class "text-right" :title "Changes in the last 30 days"} "Rels Δ"]
      [:th {:class "text-right" :title "Changes in the last 30 days"} "Conv. Δ"]]]
    [:tbody (for [u users] (user-row today now u))]]])

(defn- stats-section
  [{:keys [fleet] :as data}]
  (let [{:keys [users active billing]} fleet]
    [:details {:class "bg-base-100 border border-base-300 rounded-box"}
     [:summary {:class "cursor-pointer px-4 py-3 flex flex-wrap gap-x-4 items-baseline tabular-nums"}
      [:h2 {:class "font-semibold mr-auto"} "Stats"]
      [:span (:total users) " users"]
      [:span (-> active :last_7d :count) " active 7 d"]
      [:span (-> active :last_30d :count) " active 30 d"]
      [:span {:class "opacity-60"} (:overdue billing) " overdue"]]
     [:div {:class "border-t border-base-300 p-4 flex flex-col gap-4"}
      (tiles fleet)
      (users-table data)
      [:p {:class "text-xs opacity-60"}
       "Δ is the number of changes in the last 30 UTC days. Activity counts "
       "changes only, not visits. The console shows counts and timestamps only."]]]))

;;; Page

(defn page
  "The console page for `data`.

     {:fleet <stats/fleet>  :users <stats/user-activity>
      :env   <environment>  :today <LocalDate>  :now <OffsetDateTime>}"
  [{:keys [env] :as data}]
  (str
   (html
    (raw "<!DOCTYPE html>")
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:meta {:name "robots" :content "noindex, nofollow"}]
      [:title "Operator console – Parts"]
      [:link {:rel "stylesheet" :href (partials/asset-url "/css/style.css")}]]
     [:body {:class "bg-base-200 text-base-content min-h-screen"}
      [:header {:class "bg-base-100 border-b border-base-300 px-4 py-2 flex flex-wrap items-center gap-3"}
       [:span {:class "font-bold"} "Parts " [:span {:class "font-normal opacity-60"} "Operator console"]]
       [:span {:class (if (= env :prod)
                        "badge badge-sm badge-soft badge-error font-semibold"
                        "badge badge-sm badge-ghost")}
        (name env)]]
      [:main {:class "max-w-6xl mx-auto px-4 py-6 flex flex-col gap-6"}
       (stats-section data)]]])))
