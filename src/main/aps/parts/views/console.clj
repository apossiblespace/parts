(ns aps.parts.views.console
  "HTML of the Operator console page. `aps.parts.console` calls it with
   figures from `aps.parts.stats` and with the email draft.

   The stats show counts and timestamps only. They never show Map titles
   or other Map content. See 'Operator console' in `CONTEXT.md`.

   `/console.js` updates the email preview while the operator types. The
   page works without it, but the preview then changes only on a reload."
  (:require
   [aps.parts.stats :as stats]
   [aps.parts.views.partials :as partials]
   [hiccup2.core :refer [html raw]]
   [ring.middleware.anti-forgery :refer [*anti-forgery-token*]])
  (:import
   (java.time Duration LocalDate OffsetDateTime ZoneOffset)
   (java.time.format DateTimeFormatter)))

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

;;; Email

(def ^:private kind-names
  {"product-update" "Product update" "service-notice" "Service notice"})

(defn- audience-math
  "The recipient count of step 3, written as a subtraction."
  [{:keys [users pending opted-out recipients]}]
  (let [line (fn [label k n]
               (list [:span label] [:span [:span {:data-audience k} n]]))]
    [:div {:class "grid grid-cols-[auto_auto] justify-start gap-x-4 text-sm tabular-nums"}
     (line "Users" "users" users)
     (line "− Pending deletion" "pending" pending)
     (line "− Opted out of product updates" "opted-out" opted-out)
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
   [:form {:id "composer" :method "post" :action "/test" :class "flex flex-col gap-6"}
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
                     :formaction "/send"
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
                         [:a {:href "#sent" :class "link"} "Resume in the Sent list"])
          :done    (list [:b "Sent"] " to " (:sent email) " of " total
                         (when (pos? failed) (str " · " failed " failed"))))]
       (progress-bar email "h-1")])))

(defn- resume-form
  [id label disabled?]
  [:form {:method "post" :action (str "/sends/" id "/resume")}
   [:input {:type "hidden" :name "__anti-forgery-token" :value *anti-forgery-token*}]
   [:button {:type "submit" :class "btn btn-xs" :disabled disabled?} label]])

(defn- sent-row
  [sending? {:keys [id kind subject created_at state sent failed total failures retryable] :as email}]
  [:tr {:class (when (= state :sending) "bg-warning/10")}
   [:td {:class "whitespace-normal"}
    subject
    (when (seq failures)
      [:details {:class "text-xs mt-1"}
       [:summary {:class "cursor-pointer opacity-70"} (count failures) " failed addresses"]
       [:ul {:class "list-disc pl-5"}
        (for [{:keys [email error]} failures]
          [:li email " · " [:span {:class "opacity-70"} error]])]])]
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
   [:td (case state
          :sending [:span {:class "badge badge-sm badge-ghost"} "sending"]
          :stopped (resume-form id "Resume" sending?)
          :done    (if (pos? retryable)
                     (resume-form id "Retry failed" sending?)
                     [:span {:class "badge badge-sm badge-soft badge-success"} "done"]))]])

(defn- sent-section
  [{:keys [sent sending]}]
  (when (seq sent)
    [:section {:id "sent" :class "flex flex-col gap-2"}
     [:h2 {:class "font-semibold"} "Sent"]
     [:div {:class "overflow-x-auto bg-base-100 border border-base-300 rounded-box"}
      [:table {:class "table table-sm tabular-nums whitespace-nowrap"}
       [:thead [:tr [:th "Subject"] [:th "Kind"] [:th "Started (UTC)"] [:th "Sent"] [:th "Failed"] [:th]]]
       [:tbody (for [email sent] (sent-row (some? sending) email))]]]]))

;;; Page

(defn page
  "The console page for `data`.

     {:fleet    <stats/fleet>  :users   <stats/user-activity>
      :env      <environment>  :today   <LocalDate>  :now <OffsetDateTime>
      :draft    {:kind :subject :body}  :preview {:text :html}
      :status   {:tested? :text}        :operator <test address or nil>
      :audience <operator-email/audience> :sent <operator-email/sent-emails>
      :sending  <the summary in :sent that is sending, or nil>
      :identity <the company line, or nil>
      :error    <message or nil>}"
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
      [:link {:rel "stylesheet" :href (partials/asset-url "/css/style.css")}]
      [:script {:src (partials/asset-url "/console.js") :defer true}]]
     [:body {:class "bg-base-200 text-base-content min-h-screen"}
      (banner data)
      [:header {:class "bg-base-100 border-b border-base-300 px-4 py-2 flex flex-wrap items-center gap-3"}
       [:span {:class "font-bold"} "Parts " [:span {:class "font-normal opacity-60"} "Operator console"]]
       [:span {:class (if (= env :prod)
                        "badge badge-sm badge-soft badge-error font-semibold"
                        "badge badge-sm badge-ghost")}
        (name env)]]
      [:main {:class "max-w-6xl mx-auto px-4 py-6 flex flex-col gap-6"}
       (stats-section data)
       (email-section data)
       (sent-section data)]]])))
