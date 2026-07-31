(ns aps.parts.frontend.components.account
  "Account page (/app/account). Reachable from the auth menu on the Maps
   list. Shows the account's good-standing window and, while the page is
   still being built out, points the user at concierge support for any
   account, billing, or closure requests."
  (:require
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
  "View-model status tone → daisyUI status colour; :neutral stays the
   base (uncoloured) dot."
  {:success "status-success"
   :info    "status-info"
   :warning "status-warning"
   :error   "status-error"
   :neutral ""})

(defui ^:private subscribe-buttons
  [{:keys [pending]}]
  ($ :div {:class "flex flex-wrap gap-2 mt-3"}
     (for [{:keys [plan label primary?]} c/subscription-plans]
       ($ billing-button {:key      (name plan)
                          :label    label
                          :target   plan
                          :primary? primary?
                          :pending  pending}))))

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
        {:keys [action standing-line status]}   (account-view/billing-view
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
    ;; re-ask the server every few seconds so the card flips to the real
    ;; subscribed state on its own — no reload. Capped: past a minute of
    ;; polling something is genuinely delayed, and the message stands.
    (use-effect
     (fn []
       (if (and (= :activating action) (< poll-count 24))
         (let [timer (js/setTimeout (fn []
                                      (set-poll-count! inc)
                                      (rf/dispatch [:auth/check-auth]))
                                    2500)]
           #(js/clearTimeout timer))
         js/undefined))
     [action poll-count])
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
                                :class    "input input-sm w-64"
                                :type     "text"
                                :value    draft
                                :onChange #(set-draft! (.. % -target -value))})
                     ($ :button {:type     "submit"
                                 :class    "btn btn-sm"
                                 :disabled (nil? commit)}
                        "Save"))
                  (when update-error
                    ($ :p {:class "text-sm text-error mt-1"} update-error))
                  ($ :p {:class "text-sm text-gray-400 mt-1"}
                     "Signed in as " (:email user) "."))
               ($ :p {:class "text-base text-gray-400"} "Checking…")))

          ($ :h2 {:class "text-md font-semibold mb-2 mt-8"} "Billing")
          ($ banner {:variant :info :class "mb-2"}
             ($ :p
                "A subscription is not required to use Parts while in beta. "
                "Subscribing now helps fund development."))
          ($ :div
             (when status
               ($ :div {:class "flex items-center gap-2 mb-2"}
                  ($ :span {:class       (str "status "
                                              (subscription-status-class (:tone status)))
                            :aria-hidden "true"})
                  ($ :span {:class "text-sm text-gray-500"} (:label status))))
             (when checkout-thanks?
               ($ banner {:variant :success :class "mb-2"}
                  ($ :p "Thank you for subscribing! Your payment went through.")))
             (if (= :loading action)
               ($ :p {:class "text-base text-gray-400"} "Checking…")
               ($ :<>
                  (when standing-line
                    ($ :p {:class "text-base"} standing-line))
                  (case action
                    :activating
                    ($ :p {:class "text-base text-gray-500"}
                       ($ :span {:class "loading loading-spinner loading-xs mr-2"})
                       "Finalising your subscription…")

                    :subscribe
                    ($ :div {:class "mt-1"}
                       ($ :p {:class "text-base"}
                          "All features are included with each plan. Yearly "
                          "subscriptions include two free months.")
                       ($ subscribe-buttons {:pending billing-pending}))

                    ;; The cancelled line above already explains; offer the
                    ;; way back in without re-pitching the beta.
                    :resubscribe
                    ($ :div {:class "mt-1"}
                       ($ :p {:class "text-sm text-gray-400"}
                          "You're welcome to resubscribe at any time.")
                       ($ subscribe-buttons {:pending billing-pending}))

                    :manage
                    ($ :div {:class "mt-1"}
                       ($ :p {:class "text-sm text-gray-400"}
                          "Update your payment details, switch between "
                          "monthly and yearly, or cancel — any time.")
                       ($ :div {:class "mt-2"}
                          ($ billing-button {:label   "Manage subscription"
                                             :target  :portal
                                             :pending billing-pending})))

                    :none nil)
                  (when billing-error
                    ($ :p {:class "text-sm text-error mt-1"} billing-error)))))

          ($ app-footer)))))
