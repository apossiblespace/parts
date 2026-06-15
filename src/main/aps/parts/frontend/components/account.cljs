(ns aps.parts.frontend.components.account
  "Account page (/app/account). Reachable from the auth menu on the Maps
   list. Shows the account's good-standing window and, while the page is
   still being built out, points the user at concierge support for any
   account, billing, or closure requests."
  (:require
   [aps.parts.common.constants :as c]
   [aps.parts.frontend.components.app-footer :refer [app-footer]]
   [aps.parts.frontend.components.app-header :refer [app-header]]
   [clojure.string :as str]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect]]
   [uix.re-frame :as uix.rf]))

(def ^:private month-names
  ["January" "February" "March" "April" "May" "June" "July"
   "August" "September" "October" "November" "December"])

(defn- fmt-iso-date
  "Render an ISO `YYYY-MM-DD` string as e.g. `8 July 2026` by splitting the
   string — never via `js/Date`, so a date-only value can't drift a day
   across timezones. Returns nil for anything that doesn't parse."
  [iso]
  (when iso
    (let [[y m d] (str/split iso #"-")
          mi      (when m (dec (js/parseInt m 10)))]
      (when (and y mi d (<= 0 mi 11))
        (str (js/parseInt d 10) " " (nth month-names mi) " " y)))))

(defn- standing-message
  "Plain-language good-standing line from the server's `:standing` summary
   (see `aps.parts.billing/account-standing`). Returns nil when the summary
   isn't loaded yet, so the caller can show a placeholder."
  [{:keys [status days_remaining paid_through_date]}]
  (let [through (fmt-iso-date paid_through_date)]
    (case status
      :paid       (cond
                    (nil? days_remaining)
                    (str "Your subscription is active through " through ".")

                    (zero? days_remaining)
                    (str "Your subscription is active through the end of today (" through ").")

                    :else
                    (str "Your subscription is active for another "
                         days_remaining (if (= 1 days_remaining) " day" " days")
                         " — through " through "."))
      :overdue    (str "Your subscription lapsed on " through ".")
      :never-paid "We don't have a renewal date on file for your account yet."
      nil)))

(defui account []
  (let [user     (uix.rf/use-subscribe [:auth/user])
        standing (:standing user)]
    ;; Refresh the user — and its server-computed `:standing` — on mount.
    ;; The login response carries no standing, so a user who lands here
    ;; straight after signing in (no page reload) would otherwise miss it.
    (use-effect
     (fn []
       (rf/dispatch [:auth/check-auth])
       js/undefined)
     [])
    ($ :div {:class "min-h-screen bg-gray-50 p-4 flex flex-col"}
       ($ :div {:class "max-w-3xl mx-auto w-full flex flex-col flex-1"}
          ($ app-header)
          ($ :h1 {:class "text-lg font-bold mb-6"} "Account")

          ($ :div {:role "alert" :class "alert alert-info alert-outline mb-4"}
             ($ :svg
                {:xmlns   "http://www.w3.org/2000/svg",
                 :fill    "none",
                 :viewBox "0 0 24 24",
                 :class   "stroke-info h-6 w-6 shrink-0"}
                ($ :path
                   {:stroke-linecap  "round",
                    :stroke-linejoin "round",
                    :stroke-width    "2",
                    :d               "M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"}))
             ($ :p
                "This page is still in development. To make any changes to your account, "
                "for any billing inquiries, or to close your account and delete your "
                "information, please email us at "
                ($ :a {:href  (str "mailto:" c/support-email)
                       :class "link link-primary"}
                   c/support-email)
                "."))

          ($ :div {:class "bg-white border border-base-300 rounded-lg shadow-sm p-4 mb-4"}
             ($ :h2 {:class "text-sm font-semibold text-gray-500 mb-1"} "Billing")
             (if-let [msg (standing-message standing)]
               ($ :p {:class "text-base"} msg)
               ($ :p {:class "text-base text-gray-400"} "Checking…")))

          ($ app-footer)))))
