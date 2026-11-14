(ns aps.parts.views.console
  "HTML of the Operator console pages: the Stats tab (growth charts and the
   Users table), the Emails tab (compose and the sent list) and the page of
   one sent email. `aps.parts.console` calls it with figures from
   `aps.parts.stats` and with the email draft.

   The stats show counts and timestamps only. They never show Map titles
   or other Map content. See 'Operator console' in `CONTEXT.md`.

   `/console.js` updates the email preview while the operator types. The
   page works without it, but the preview then changes only on a reload."
  (:require
   [aps.parts.stats :as stats]
   [aps.parts.views.partials :as partials]
   [clojure.string :as str]
   [hiccup2.core :refer [html raw]]
   [ring.middleware.anti-forgery :refer [*anti-forgery-token*]])
  (:import
   (java.time Duration LocalDate OffsetDateTime ZoneOffset)
   (java.time.format DateTimeFormatter)
   (java.util Locale)))

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

(def ^:private short-date (DateTimeFormatter/ofPattern "d MMM" Locale/ENGLISH))

(defn- days-left [days]
  (if (zero? days) "last day" (str days " d left")))

(defn- standing-badge
  "Returns the badge and the time left of a `billing/operator-standing`."
  [{:keys [kind days plan since]}]
  (let [badge (fn [cls label] [:span {:class (str "badge badge-sm badge-soft " cls)} label])
        time  (fn [text] [:span {:class "ml-1 opacity-70"} text])]
    (case kind
      :trial           (list (badge (if (<= days 7) "badge-warning" "badge-info") "trial")
                             (time (days-left days)))
      :subscribed      (list (badge "badge-success" (str "subscribed" (when plan (str " · " plan))))
                             (when days (time (days-left days))))
      :payment-failing (badge "badge-error" "payment failing")
      :paid            (list (badge "badge-success" "paid") (time (days-left days)))
      :comp            (list (badge "badge-accent" "comp") (time (days-left days)))
      :lapsed          (list (badge "badge-error" "lapsed")
                             (time (str "since " (.format short-date ^LocalDate since))))
      :free            [:span.badge.badge-sm.badge-ghost "free"])))

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

(defn- user-row
  [today now {:keys [display_name email created_at is_founding_circle
                     product_updates_opted_out standing last_active active_days counts]}]
  [:tr
   [:td
    [:div {:class "flex flex-col"}
     [:span display_name " "
      (when is_founding_circle
        [:span {:class "badge badge-xs badge-ghost" :title "Founding Circle"} "FC"])
      " "
      (when product_updates_opted_out
        [:span {:class "badge badge-xs badge-outline opacity-60"
                :title "Opted out of product updates"}
         "no updates"])]
     [:span {:class "text-xs opacity-60"} email]]]
   [:td {:class "opacity-70"}
    (str (-> ^java.sql.Timestamp created_at .toInstant (.atOffset ZoneOffset/UTC) .toLocalDate))]
   [:td (standing-badge standing)]
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
      [:th "Standing"]
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

(def ranges
  "The chart ranges of the Stats tab: query value, label and days."
  [["30d" "30 days" 30] ["3m" "3 months" 91] ["6m" "6 months" 182] ["1y" "1 year" 365]])

(defn- nice-ceiling
  "Returns a round number at or above `x`, for the top of the y-axis."
  [x]
  (let [x (max 1 x)
        p (Math/pow 10 (Math/floor (Math/log10 x)))]
    (long (* p (some #(when (>= (* % p) x) %) [1 2 2.5 5 10])))))

(defn- chart
  "An inline SVG line chart of `points` ({:date :value}, oldest first, a
   nil value where there is no data). A dashed line marks `estimated-to`,
   the first day that is not a back-filled estimate. The last point is
   today, which is not over yet, so it is drawn lighter."
  [points estimated-to]
  (let [w       300
        h       90
        pad-x   4
        pad-t   14
        pad-b   16
        n       (count points)
        top     (nice-ceiling (reduce max 0 (keep :value points)))
        num     #(str (/ (Math/round (* 10.0 %)) 10.0))
        x       #(num (+ pad-x (* (- w pad-x pad-x) (/ % (max 1 (dec n))))))
        y       #(num (- h pad-b (* (- h pad-t pad-b) (/ % top))))
        known   (keep-indexed (fn [i p] (when (:value p) [i p])) points)
        est-i   (some (fn [[i p]] (when (= estimated-to (:date p)) i)) known)
        [li lp] (last known)
        label   (fn [attrs text]
                  [:text (merge {:font-size 9 :fill "currentColor" :fill-opacity 0.5} attrs) text])]
    [:svg {:viewBox (str "0 0 " w " " h) :class "w-full h-auto block text-primary-content" :role "img"}
     [:line {:x1             pad-x
             :x2             (- w pad-x)
             :y1             (y 0)
             :y2             (y 0)
             :stroke         "currentColor"
             :stroke-opacity 0.15}]
     (label {:x pad-x :y 10} (str top))
     (label {:x pad-x :y (- h 3)} (.format short-date ^LocalDate (:date (first points))))
     (label {:x (- w pad-x) :y (- h 3) :text-anchor "end"} "today")
     (when est-i
       [:line {:x1               (x est-i)
               :x2               (x est-i)
               :y1               pad-t
               :y2               (y 0)
               :stroke           "currentColor"
               :stroke-opacity   0.25
               :stroke-dasharray "2 2"}
        [:title (str "Estimated before " (.format short-date ^LocalDate estimated-to))]])
     ;; One line for each run of days with data, so a gap stays a gap.
     (for [run   (partition-by (comp nil? :value second) (map-indexed vector points))
           :when (:value (second (first run)))]
       [:polyline {:points          (str/join " " (for [[i p] run] (str (x i) "," (y (:value p)))))
                   :fill            "none"
                   :stroke          "currentColor"
                   :stroke-width    1.5
                   :stroke-linejoin "round"
                   :stroke-linecap  "round"}])
     ;; An invisible circle on each day shows the value on hover.
     (for [[i p] known]
       [:circle {:cx (x i) :cy (y (:value p)) :r 3 :fill "transparent"}
        [:title (str (.format short-date ^LocalDate (:date p)) ": " (:value p))]])
     (when lp
       [:circle {:cx (x li) :cy (y (:value lp)) :r 2.5 :fill "currentColor" :fill-opacity 0.4}])]))

(defn- change-line
  "The change over the points that have data, as \"+12 since 18 Sep\"."
  [points]
  (let [known (filter :value points)]
    (if (< (count known) 2)
      "no history yet"
      (let [d     (- (:value (last known)) (:value (first known)))
            since (str " since " (.format short-date ^LocalDate (:date (first known))))]
        (cond (pos? d) (str "+" d since)
              (neg? d) (str d since)
              :else    "no change")))))

(defn- metrics
  "One `[history-key label live-value detail]` for each chart of `fleet`."
  [{:keys [users active founding_circle product_updates subscribers payment_failing]}]
  (let [share #(format "%.0f%% of users" (-> active % :pct))]
    [[:users "Users" (:total users) (str founding_circle " founding")]
     [:active_day "Active today" (-> active :today :count) (share :today)]
     [:active_7d "Active 7 d" (-> active :last_7d :count) (share :last_7d)]
     [:active_30d "Active 30 d" (-> active :last_30d :count) (share :last_30d)]
     [:subscribers "Subscribers" subscribers (str payment_failing " payment failing")]
     [:product_updates "Product updates" (:subscribed product_updates)
      (str (:opted_out product_updates) " opted out · " (:not_asked product_updates) " not asked")]]))

(defn- card
  [label live detail points estimated-to]
  [:div {:class "bg-base-100 border border-base-300 rounded-box p-3 flex flex-col gap-1 min-w-0"}
   [:div {:class "flex items-baseline justify-between gap-2"}
    [:span {:class "text-xs font-semibold uppercase tracking-wide opacity-60"} label]
    [:span {:class "text-xs opacity-60 tabular-nums"} (change-line points)]]
   [:span {:class "text-2xl font-semibold tabular-nums"} live]
   (chart points estimated-to)
   [:span {:class "text-xs opacity-60 tabular-nums"} detail]])

(defn- estimated-to
  "The first day of `history` with snapshot counts after a day without
   them: the end of the back-filled estimates. nil when the range shows no
   such change."
  [history]
  (some (fn [[a b]] (when (and (nil? (:subscribers a)) (:subscribers b)) (:day b)))
        (partition 2 1 history)))

(defn- growth
  [{:keys [fleet history days ^LocalDate today] range-key :range}]
  (let [by-day (into {} (map (juxt :day identity)) history)
        dates  (map #(.minusDays today %) (range (dec days) -1 -1))
        est    (estimated-to history)]
    [:section {:class "flex flex-col gap-3"}
     [:div {:class "flex flex-wrap items-center justify-between gap-3"}
      [:h2 {:class "font-semibold"} "Growth"]
      [:div {:class "join"}
       (for [[k label] ranges]
         [:a {:class (str "btn btn-sm join-item" (when (= k range-key) " btn-active"))
              :href  (str "/stats?range=" k)}
          label])]]
     [:div {:class "grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3"}
      (for [[k label live detail] (metrics fleet)]
        (card label live detail
              (for [d dates]
                {:date d :value (if (= d today) live (get-in by-day [d k]))})
              est))]
     [:p {:class "text-xs opacity-60"}
      "Each point is the count at the end of a UTC day; today is not over yet. "
      "Hover a line for the value of a day. Before the dashed line, Users and Active "
      "are estimates: they leave out deleted accounts. Subscribers and Product updates "
      "start at the dashed line."]]))

(defn- standing-summary
  "Returns the line of `billing/operator-standing` counts above the Users
   table."
  [{:keys [trial trial-ending subscribed payment-failing paid comp lapsed free]}]
  [:p {:class "text-sm opacity-70 tabular-nums"}
   (str/join " · " (cond-> [(str "Trial " trial " (" trial-ending " ending in 7 d)")
                            (str "Subscribed " subscribed)
                            (str "Payment failing " payment-failing)
                            (str "Paid " paid)
                            (str "Comp " comp)
                            (str "Lapsed " lapsed)]
                     (pos? free) (conj (str "Free " free))))])

(defn- stats-tab
  [data]
  (list
   (growth data)
   [:section {:class "flex flex-col gap-2"}
    [:h2 {:class "font-semibold"} "Users"]
    (standing-summary (:standing (:fleet data)))
    (users-table data)
    [:p {:class "text-xs opacity-60"}
     "Δ is the number of changes in the last 30 UTC days. Activity counts "
     "changes only, not visits. The console shows counts and timestamps only."]]))

;;; Email

(def ^:private kind-names
  {"service-notice" "Service notice" "product-update" "Product update"})

(defn- audience-math
  "The recipient count of step 3, written as a subtraction."
  [{:keys [users not-subscribed recipients]}]
  (let [line (fn [label k n]
               (list [:span label] [:span [:span {:data-audience k} n]]))]
    [:div {:class "grid grid-cols-[auto_auto] justify-start gap-x-4 text-sm tabular-nums"}
     (line "Users" "users" users)
     (line "− Not subscribed to product updates" "not-subscribed" not-subscribed)
     [:span {:class "font-semibold border-t border-base-300"} "Recipients"]
     [:span {:class "font-semibold border-t border-base-300"}
      [:span {:data-audience "recipients"} recipients]]]))

(defn- step
  "A numbered step of the email flow."
  [n title & body]
  [:div {:class "grid grid-cols-[2rem_1fr] gap-3"}
   [:span {:class "w-7 h-7 rounded-full bg-base-300 grid place-items-center text-sm font-bold"} n]
   [:div {:class "flex flex-col gap-3 min-w-0"}
    [:h3 {:class "font-semibold leading-7"} title]
    body]])

(defn- preview-pane
  [{:keys [html text]}]
  [:div {:class "flex flex-col gap-2 min-w-0"}
   ;; Both column headers are h-6, so the editor and the preview line up.
   [:div {:class "flex items-center justify-between gap-2 h-6"}
    [:span {:class "text-xs font-semibold uppercase tracking-wide opacity-60"} "Preview"]
    [:div {:class "join"}
     [:button {:type         "button" :class "join-item btn btn-xs btn-active" :data-preview "html"
               :aria-pressed "true"}
      "HTML"]
     [:button {:type         "button" :class "join-item btn btn-xs" :data-preview "text"
               :aria-pressed "false"}
      "Plain text"]]]
   [:iframe {:id      "preview-html"
             :title   "Email preview"
             :sandbox ""
             :srcdoc  html
             :class   "w-full h-[28rem] bg-white border border-base-300 rounded-box"}]
   [:pre {:id     "preview-text"
          :hidden true
          :class  "w-full h-[28rem] overflow-auto whitespace-pre-wrap p-3 text-sm bg-base-100 border border-base-300 rounded-box"}
    text]])

(defn- email-section
  [{:keys [draft preview status operator audience sending identity error]}]
  [:section {:class "flex flex-col gap-4"}
   [:h2 {:class "font-semibold"} "Send an email"]
   (when error
     [:div {:role "alert" :class "alert alert-error alert-soft text-sm"} error])
   [:form {:id "composer" :method "post" :action "/emails/test" :class "flex flex-col gap-6"}
    [:input {:type "hidden" :name "__anti-forgery-token" :value *anti-forgery-token*}]
    ;; Enter in a text field submits with the first submit button. This
    ;; button is disabled, so Enter in the subject does not send a test.
    [:button {:type "submit" :disabled true :hidden true :aria-hidden "true"}]
    (step 1 "Write"
          [:div {:class "join"}
           (for [[kind label] kind-names]
             [:input {:type       "radio"
                      :name       "kind"
                      :value      kind
                      :aria-label label
                      :class      "join-item btn btn-sm"
                      :checked    (= kind (:kind draft))}])]
          [:label {:class "flex flex-col gap-1"}
           [:span {:class "text-xs font-semibold opacity-60"} "Subject"]
           [:input {:type  "text"                  :name         "subject" :value (:subject draft)
                    :class "input input-sm w-full" :autocomplete "off"}]]
          [:div {:class "grid md:grid-cols-2 gap-4"}
           [:label {:class "flex flex-col gap-2 min-w-0"}
            [:span {:class "flex items-center justify-between gap-2 h-6"}
             [:span {:class "text-xs font-semibold uppercase tracking-wide opacity-60"} "Markdown"]]
            [:textarea {:name  "body"
                        :class "textarea w-full h-[28rem] font-mono text-sm"}
             ;; The HTML parser drops a newline right after <textarea>, so
             ;; one is added to keep a body that starts with a newline.
             (str "\n" (:body draft))]]
           (preview-pane preview)])
    (step 2 "Test"
          [:div {:class "flex flex-wrap items-center gap-3"}
           ;; formnovalidate, so that the required checkbox of step 3
           ;; does not block a test send.
           [:button {:type "submit" :class "btn btn-sm" :formnovalidate true}
            "Send test" (when operator (str " to " operator))]
           [:span {:id    "test-status"
                   :class (if (:tested? status) "text-sm text-success" "text-sm opacity-70")}
            (:text status)]])
    (step 3 "Send"
          (audience-math audience)
          [:label {:class "flex items-center gap-2 text-sm"}
           [:input {:type "checkbox" :name "ack" :required true :class "checkbox checkbox-sm"}]
           "I checked the test email."]
          [:div
           [:button {:type       "submit"
                     :id         "send-button"
                     :formaction "/emails/send"
                     :class      "btn btn-sm btn-primary"
                     :disabled   (or sending (not identity) (not (:tested? status)))}
            "Send to " [:span {:data-audience "recipients"} (:recipients audience)] " Users"]]
          (when-not identity
            [:p {:class "text-sm text-error"}
             "Set PARTS__MAIL__SENDER_IDENTITY, the company line, before sending to Users."])
          (when sending
            [:p {:class "text-sm opacity-70"} "Another email is sending. Wait until it ends."]))]])

;;; Sent emails

(defn- utc-time
  "Returns the JDBC timestamp `ts` as \"9 Oct 2026, 14:02\" in UTC."
  [^java.sql.Timestamp ts]
  (.format (DateTimeFormatter/ofPattern "d MMM yyyy, HH:mm")
           (.atOffset (.toInstant ts) ZoneOffset/UTC)))

(defn- width [n total]
  (str "width:" (if (pos? total) (/ (* 100.0 n) total) 0) "%"))

(defn- progress-bar
  "A bar of the sent and failed share of `email`. `console.js` updates the
   two parts through their `data-bar` attributes."
  [{:keys [sent failed total]} height]
  [:div {:class         (str "flex overflow-hidden rounded-full bg-base-300 " height)
         :role          "progressbar"
         :aria-valuemin 0
         :aria-valuemax total
         :aria-valuenow (+ sent failed)}
   [:i {:class "block h-full bg-success" :data-bar "sent" :style (width sent total)}]
   [:i {:class "block h-full bg-error" :data-bar "failed" :style (width failed total)}]])

;; The banner follows the email that is sending, which can be an older
;; email that was resumed. With no send, it follows the newest email while
;; it is stopped and for 10 minutes after it ends. `data-sending` tells
;; `console.js` to poll for progress.
(defn- banner
  [{:keys [sent sending now]}]
  (let [{:keys [id state subject failed total completed_at] :as email} (or sending (first sent))
        done                                                           (+ (:sent email 0) (or failed 0))]
    (when (or (#{:sending :stopped} state)
              (and (= state :done)
                   (.isAfter (.toInstant ^java.sql.Timestamp completed_at)
                             (.toInstant (.minusMinutes ^OffsetDateTime now 10)))))
      [:div {:role         "status"
             :data-sending (when (= state :sending) (str id))
             :class        "sticky top-0 z-10 bg-base-100 border-b border-base-300 px-4 py-2 flex flex-col gap-1"}
       [:div {:class "text-sm tabular-nums"}
        (case state
          :sending (list [:b "Sending"] " “" subject "” · "
                         [:span {:data-progress "done"} done] " of "
                         [:span {:data-progress "total"} total] " · "
                         [:span {:data-progress "failed"} failed] " failed")
          :stopped (list [:b "Stopped"] " at " done " of " total " · "
                         [:a {:href "/emails#sent" :class "link"} "Resume in the Sent list"])
          :done    (list [:b "Sent"] " to " (:sent email) " of " total
                         (when (pos? failed) (str " · " failed " failed"))))]
       (progress-bar email "h-1")])))

(defn- post-button
  "A one-button form that posts to `action`."
  [action label attrs]
  [:form {:method "post" :action action}
   [:input {:type "hidden" :name "__anti-forgery-token" :value *anti-forgery-token*}]
   [:button (merge {:type "submit" :class "btn btn-xs"} attrs) label]])

(defn- email-action
  "Resume or Retry for `email`, or a badge of its state."
  [sending? {:keys [id state retryable]}]
  (let [resume #(post-button (str "/emails/sent/" id "/resume") % {:disabled sending?})]
    (case state
      :sending [:span {:class "badge badge-sm badge-ghost"} "sending"]
      :stopped (resume "Resume")
      :done    (if (pos? retryable)
                 (resume "Retry failed")
                 [:span {:class "badge badge-sm badge-soft badge-success"} "done"]))))

(defn- failure-list
  [failures]
  (when (seq failures)
    [:details {:class "text-xs mt-1"}
     [:summary {:class "cursor-pointer opacity-70"} (count failures) " failed addresses"]
     [:ul {:class "list-disc pl-5"}
      (for [{:keys [email error]} failures]
        [:li email " · " [:span {:class "opacity-70"} error]])]]))

(defn- sent-row
  [sending? {:keys [id kind subject created_at state sent failed total failures] :as email}]
  [:tr {:class (when (= state :sending) "bg-warning/10")}
   [:td {:class "whitespace-normal"}
    [:a {:class "link link-hover" :href (str "/emails/sent/" id)} subject]
    (failure-list failures)]
   [:td {:class "opacity-70"} (kind-names kind)]
   [:td {:class "opacity-70"} (utc-time created_at)]
   [:td (if (= state :sending)
          [:div {:class "flex flex-col gap-1 min-w-32"}
           [:span [:span {:data-progress "sent"} sent] " of " [:span {:data-progress "total"} total]]
           (progress-bar email "h-1")]
          sent)]
   [:td (cond
          (= state :sending) [:span {:data-progress "failed"} failed]
          (pos? failed)      [:span {:class "badge badge-sm badge-soft badge-error"} failed " failed"]
          :else              "—")]
   [:td (email-action sending? email)]])

(defn- sent-section
  [{:keys [sent sending]}]
  (when (seq sent)
    [:section {:id "sent" :class "flex flex-col gap-2"}
     [:h2 {:class "font-semibold"} "Sent"]
     [:div {:class "overflow-x-auto bg-base-100 border border-base-300 rounded-box"}
      [:table {:class "table table-sm tabular-nums whitespace-nowrap"}
       [:thead [:tr [:th "Subject"] [:th "Kind"] [:th "Started (UTC)"] [:th "Sent"] [:th "Failed"] [:th]]]
       [:tbody (for [email sent] (sent-row (some? sending) email))]]]]))

(defn- sent-email
  "The page body of one sent email: its facts, the email as it looks with
   today's layout, and its Markdown source."
  [{:keys [email preview sending]}]
  (let [{:keys [id kind subject body created_at completed_at sent failed total failures]} email]
    [:section {:class "flex flex-col gap-4"}
     [:a {:href "/emails" :class "link link-hover text-sm"} "← Emails"]
     [:div {:class "flex flex-wrap items-center justify-between gap-3"}
      [:h2 {:class "font-semibold text-lg"} subject]
      [:div {:class "flex items-center gap-2"}
       (email-action (some? sending) email)
       (post-button (str "/emails/sent/" id "/draft") "Use as draft" {})]]
     [:p {:class "text-sm opacity-70 tabular-nums"}
      (kind-names kind) " · started " (utc-time created_at)
      (when completed_at (str " · finished " (utc-time completed_at)))
      " · sent to " sent " of " total
      (when (pos? failed) (str " · " failed " failed"))]
     (failure-list failures)
     [:iframe {:title   "The sent email"
               :sandbox ""
               :srcdoc  (:html preview)
               :class   "w-full h-[32rem] bg-white border border-base-300 rounded-box"}]
     [:details {:class "bg-base-100 border border-base-300 rounded-box"}
      [:summary {:class "cursor-pointer px-3 py-2 text-sm"} "Markdown source"]
      [:pre {:class "border-t border-base-300 p-3 text-sm whitespace-pre-wrap"} body]]]))

;;; Page

(defn- tabs
  "The Stats / Emails control. While an email sends, the Emails tab shows
   its progress, and `data-sending` tells `console.js` to poll."
  [current sending]
  (let [tab (fn [k href label & more]
              [:a {:class (str "btn btn-sm join-item gap-1.5" (when (= k current) " btn-active"))
                   :href  href}
               label more])]
    [:nav {:class "join ml-auto" :aria-label "Console"}
     (tab :stats "/stats" "Stats")
     (tab :emails "/emails" "Emails"
          (when-let [{:keys [id sent failed total]} sending]
            [:span {:class "badge badge-xs badge-soft badge-info tabular-nums" :data-sending (str id)}
             "Sending " [:span {:data-progress "done"} (+ sent failed)]
             " of " [:span {:data-progress "total"} total]]))]))

(defn- layout
  [{:keys [env tab sending] :as data} & content]
  (str
   (html
    (raw "<!DOCTYPE html>")
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:meta {:name "robots" :content "noindex, nofollow"}]
      [:title "Operator console – Parts"]
      [:link {:rel "stylesheet" :href (partials/asset-url "/css/style.css")}]
      [:script {:src (partials/asset-url "/console.js") :defer true}]]
     [:body {:class "bg-base-200 text-base-content min-h-screen"}
      (when (= tab :emails) (banner data))
      [:header {:class "bg-base-100 border-b border-base-300 px-4 py-2 flex flex-wrap items-center gap-3"}
       [:span {:class "font-bold"} "Parts " [:span {:class "font-normal opacity-60"} "Operator console"]]
       [:span {:class (if (= env :prod)
                        "badge badge-sm badge-soft badge-error font-semibold"
                        "badge badge-sm badge-ghost")}
        (name env)]
       (tabs tab sending)]
      [:main {:class "max-w-6xl mx-auto px-4 py-6 flex flex-col gap-6"}
       content]]])))

(defn stats-page
  "The Stats tab.

     {:fleet   <stats/fleet>   :users <stats/user-activity>
      :history <stats/history> :range <key of `ranges`> :days <its days>
      :env     <environment>   :today <LocalDate> :now <OffsetDateTime>
      :sending <operator-email/current-send>}"
  [data]
  (layout (assoc data :tab :stats) (stats-tab data)))

(defn emails-page
  "The Emails tab.

     {:env      <environment>  :now <OffsetDateTime>
      :draft    {:kind :subject :body}  :preview {:text :html}
      :status   {:tested? :text}        :operator <test address or nil>
      :audience <operator-email/audience> :sent <operator-email/sent-emails>
      :sending  <the summary in :sent that is sending, or nil>
      :identity <the company line, or nil>
      :error    <message or nil>}"
  [data]
  (layout (assoc data :tab :emails)
          (email-section data)
          (sent-section data)))

(defn sent-email-page
  "The page of one sent email: `emails-page` data with `:email` (a summary
   of `:sent` with its `:body`) and its `:preview`."
  [data]
  (layout (assoc data :tab :emails) (sent-email data)))
