(ns aps.parts.frontend.components.account
  "Account page (/app/account). Reachable from the auth menu on the Maps
   list. Shows the account's good-standing window and, while the page is
   still being built out, points the user at concierge support for any
   account, billing, or closure requests."
  (:require
   ["lucide-react" :refer [Check]]
   [aps.parts.common.constants :as c]
   [aps.parts.frontend.components.account-view :as account-view]
   [aps.parts.frontend.components.app-footer :refer [app-footer]]
   [aps.parts.frontend.components.app-header :refer [app-header]]
   [aps.parts.frontend.components.banner :refer [banner]]
   [aps.parts.frontend.components.inline-edit :as inline-edit]
   [aps.parts.frontend.router :as router]
   [clojure.string :as str]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect use-state]]
   [uix.re-frame :as uix.rf]))

(defui ^:private billing-button
  [{:keys [label target primary? pending]}]
  ($ :button {:class    (str "btn btn-sm" (when primary? " btn-primary"))
              :disabled (some? pending)
              :on-click #(rf/dispatch [:billing/start target])}
     (when (= target pending)
       ($ :span {:class "loading loading-spinner loading-xs"}))
     label))

(def ^:private subscription-status-class
  "View-model status tone → daisyUI status colour."
  {:success "status-success"
   :info    "status-info"
   :warning "status-warning"
   :error   "status-error"})

(defui ^:private plan-card
  [{:keys [plan title price cadence features primary? pending cta]}]
  ($ :div {:class (str "card bg-base-100 border w-full sm:w-60"
                       (if primary? " border-primary" " border-base-300"))}
     ($ :div {:class "card-body p-2"}
        ($ :div {:class "flex justify-between items-baseline"}
           ($ :h3 {:class "text-lg font-bold"} title)
           ($ :span {:class "text-lg"} price
              ($ :span {:class "text-sm text-gray-500"} cadence)))
        ($ :ul {:class "mt-1 flex flex-col gap-2 text-sm"}
           (for [feature features]
             ($ :li {:key   feature
                     :class "flex items-center gap-2"}
                ($ Check {:class "h-4 w-4 shrink-0 text-success"})
                ($ :span feature))))
        ($ :div {:class "mt-auto pt-1"}
           ($ :button {:class    (str "btn btn-sm btn-block"
                                      (when primary? " btn-primary"))
                       :disabled (some? pending)
                       :on-click #(rf/dispatch [:billing/start plan])}
              (when (= plan pending)
                ($ :span {:class "loading loading-spinner loading-xs"}))
              (or cta "Subscribe"))))))

(defui ^:private plan-cards
  [{:keys [pending cta]}]
  ($ :div {:class "flex flex-wrap gap-4 mt-3"}
     (for [{:keys [plan] :as entry} c/subscription-plans]
       ($ plan-card (assoc entry :key (name plan) :pending pending :cta cta)))))

(defui account []
  (let [user                                    (uix.rf/use-subscribe [:auth/user])
        standing                                (:standing user)
        display-name                            (:display_name user)
        update-error                            (uix.rf/use-subscribe [:account/update-error])
        billing-error                           (uix.rf/use-subscribe [:account/billing-error])
        billing-pending                         (uix.rf/use-subscribe [:account/billing-pending])
        query-params                            (uix.rf/use-subscribe [:router/query-params])
        [checkout-thanks? set-checkout-thanks!] (use-state false)
        [poll-count set-poll-count!]            (use-state 0)
        {:keys [action status-line cta]}        (account-view/billing-view
                                                 {:billing           (:billing user)
                                                  :standing          standing
                                                  :checkout-pending? checkout-thanks?})
        [draft set-draft!]                      (use-state (or display-name ""))
        commit                                  (inline-edit/commit-value draft display-name
                                                                          (complement str/blank?))]
    ;; Seed the draft once the async user record lands — keyed on identity,
    ;; not the name, so a save echo or background auth refresh can't clobber
    ;; an uncommitted draft (the hazard use-autosave-form documents).
    (use-effect
     (fn [] (set-draft! (or display-name "")))
     ^:lint/disable [(:id user)])
    ;; Refresh the user — and its server-computed `:standing` — on mount.
    ;; The login response carries no standing, so a user who lands here
    ;; straight after signing in (no page reload) would otherwise miss it.
    (use-effect
     (fn []
       (rf/dispatch [:auth/check-auth])
       js/undefined)
     [])
    ;; Returning from a completed Stripe Checkout lands on
    ;; /app/account?checkout=success. Latch a thank-you locally, then drop
    ;; the param from the URL so a reload or bookmark doesn't re-thank.
    (use-effect
     (fn []
       (when (= "success" (:checkout query-params))
         (set-checkout-thanks! true)
         (rf/dispatch [:router/replace ::router/account]))
       js/undefined)
     [query-params])
    ;; Back from Stripe restores this page from the bfcache with
    ;; billing-pending still set; pageshow/.persisted is the only signal
    ;; on that path — without this the buttons stay stuck disabled.
    (use-effect
     (fn []
       (let [on-pageshow (fn [^js e]
                           (when (.-persisted e)
                             (rf/dispatch [:billing/settled])))]
         (.addEventListener js/window "pageshow" on-pageshow)
         #(.removeEventListener js/window "pageshow" on-pageshow)))
     [])
    ;; While activating (back from Checkout, webhook not yet landed),
    ;; re-ask the server so the card flips to the real subscribed state on
    ;; its own — no reload, ever: quick polls for the first ~30s (the
    ;; webhook normally lands in seconds), then a gentle 30s cadence
    ;; indefinitely for the delayed-delivery tail.
    (use-effect
     (fn []
       (if (= :activating action)
         (let [timer (js/setTimeout (fn []
                                      (set-poll-count! inc)
                                      (rf/dispatch [:auth/check-auth]))
                                    (if (< poll-count 12) 2500 30000))]
           #(js/clearTimeout timer))
         js/undefined))
     [action poll-count])
    ;; And refresh the moment the tab becomes visible again while
    ;; activating — returning from another tab shouldn't wait out the
    ;; slow-poll interval.
    (use-effect
     (fn []
       (if (= :activating action)
         (let [on-visible (fn []
                            (when (= "visible" (.-visibilityState js/document))
                              (rf/dispatch [:auth/check-auth])))]
           (.addEventListener js/document "visibilitychange" on-visible)
           #(.removeEventListener js/document "visibilitychange" on-visible))
         js/undefined))
     [action])
    ($ :div {:class "min-h-screen bg-gray-50 p-4 flex flex-col"}
       ($ :div {:class "max-w-3xl mx-auto w-full flex flex-col flex-1"}
          ($ app-header)
          ($ banner {:variant :warning :class "mb-4"}
             ($ :p
                "This page is still in development. To make any changes to your account, "
                "for any billing inquiries, or to close your account and delete your "
                "information, please email us at "
                ($ :a {:href (str "mailto:" c/support-email)}
                   c/support-email)
                "."))

          ($ :h1 {:class "text-lg font-bold mb-6"} "Account")

          ($ :h2 {:class "text-md font-semibold mb-2"} "Profile")
          ($ :div
             (if user
               ($ :form {:class     "fieldset"
                         :on-submit (fn [^js e]
                                      (.preventDefault e)
                                      (when commit
                                        (rf/dispatch [:account/update
                                                      {:display_name commit}])))}
                  ($ :label {:class "fieldset-label" :for "display-name"}
                     "Display name:")
                  ($ :div {:class "flex gap-2"}
                     ($ :input {:id       "display-name"
                                ;; w-full with a cap: a fixed width overflows narrow phones.
                                :class    "input input-sm w-full max-w-64"
                                :type     "text"
                                :value    draft
                                :onChange #(set-draft! (.. % -target -value))})
                     ($ :button {:type     "submit"
                                 :class    "btn btn-sm shrink-0"
                                 :disabled (nil? commit)}
                        "Save"))
                  (when update-error
                    ($ :p {:class "text-sm text-error mt-1"} update-error))
                  ($ :p {:class "text-sm text-gray-400 mt-1"}
                     "Signed in as " (:email user) "."))
               ($ :p {:class "text-sm text-gray-400"} "Checking…")))

          ($ :h2 {:class "text-md font-semibold mb-2 mt-8"} "Billing")
          ($ banner {:variant :info :class "mb-2"}
             ($ :p
                "A subscription is not required to use Parts while in beta. "
                "Subscribing now supports continued development."))
          ($ :div
             (when checkout-thanks?
               ($ banner {:variant :success :class "mb-2"}
                  ($ :p "Thank you for subscribing! Your subscription has been set up.")))
             (if (= :loading action)
               ($ :p {:class "text-sm text-gray-400"} "Checking…")
               ($ :<>
                  (when status-line
                    ($ :p {:class "text-sm mb-2"}
                       ;; Same 1lh trick as the banner icon: the wrapper is
                       ;; exactly one text line tall and top-aligned, so the
                       ;; dot centres on the first line at any font size.
                       ($ :span {:class       "inline-flex h-[1lh] items-center align-top mr-1"
                                 :aria-hidden "true"}
                          ($ :span {:class (str "status "
                                                (subscription-status-class
                                                 (:tone status-line)))}))
                       ($ :span {:class "font-medium"} (:headline status-line))
                       (:body status-line)))
                  (case action
                    :subscribe
                    ($ :div {:class "mt-1"}
                       ($ plan-cards {:pending billing-pending :cta cta}))

                    ;; The status line explains what happens next; the
                    ;; cards just offer the way back in.
                    :resubscribe
                    ($ :div {:class "mt-1"}
                       ($ plan-cards {:pending billing-pending :cta cta}))

                    (:manage :cancelling)
                    ($ :div {:class "mt-2 flex flex-wrap items-center gap-2"}
                       ($ billing-button {:label   "Manage subscription"
                                          :target  :portal
                                          :pending billing-pending})
                       ($ :p {:class "text-sm"}
                          "Update your payment details, switch between "
                          "monthly and yearly, or cancel at any time."))

                    nil)
                  (when billing-error
                    ($ :p {:class "text-sm text-error mt-1"} billing-error))))

             ($ :p {:class "text-sm mt-4 mb-8"}
                "Individual plans are valid for a single practitioner. "
                "If you wish to purchase a subscription for a group "
                "practice, please contact us at "
                ($ :a {:href (str "mailto:" c/support-email)}
                   c/support-email)
                "."))

          ($ app-footer)))))
