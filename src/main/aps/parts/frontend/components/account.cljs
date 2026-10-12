(ns aps.parts.frontend.components.account
  "Account page (/app/account). Reachable from the auth menu on the Maps
   list. Each section has its title on the left and a framed group of
   rows on the right: a row has a label (and hint) on the left and its
   control on the right. Actions that need more input open a sheet.
   While the page is still being built out, a banner points the user at
   concierge support for the account changes it cannot make yet."
  (:require
   ["lucide-react/dist/esm/icons/circle-check" :default CircleCheck]
   [aps.parts.common.constants :as c]
   [aps.parts.frontend.components.account-view :as account-view]
   [aps.parts.frontend.components.app-footer :refer [app-footer]]
   [aps.parts.frontend.components.app-header :refer [app-header]]
   [aps.parts.frontend.components.banner :refer [banner]]
   [aps.parts.frontend.components.inline-edit :as inline-edit]
   [aps.parts.frontend.components.modal :refer [modal]]
   [aps.parts.frontend.router :as router]
   [clojure.string :as str]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect use-state]]
   [uix.re-frame :as uix.rf]))

;;; Layout

(defui ^:private section
  "A titled section: the title in a fixed left column, then `children` in
   a frame. `above` and `note` render above and below the frame."
  [{:keys [title above note children]}]
  ($ :section {:class "flex flex-col md:flex-row gap-2 md:gap-8 mb-6"}
     ($ :h2 {:class "text-md font-semibold shrink-0 md:w-40 md:pt-2"} title)
     ($ :div {:class "flex-1 min-w-0"}
        above
        ($ :div {:class "rounded-box bg-base-100 border border-base-300 divide-y divide-base-300"}
           children)
        (when note
          ($ :div {:class "text-xs text-gray-500 mt-2 px-1"} note)))))

(defui ^:private row
  "A row: `label` and an optional `hint` on the left, `children` on the
   right. With `for` (the id of the control) the label is a <label>;
   `muted?` greys it, for a fact such as \"Plan\"."
  [{:keys [label for hint muted? children]}]
  ($ :div {:class "flex items-center justify-between gap-4 px-4 py-3 text-sm"}
     ($ :div {:class (str "min-w-0" (when muted? " text-gray-500"))}
        (if for ($ :label {:html-for for :class "block"} label) ($ :p label))
        (when hint ($ :p {:class "text-xs text-gray-500 break-words"} hint)))
     ($ :div {:class "flex items-center gap-2 shrink-0"} children)))

;;; Password

(defui ^:private password-field [{:keys [id label auto-complete value on-change hint]}]
  ($ :div {:class "flex flex-col gap-1"}
     ($ :label {:class "fieldset-label" :for id} label)
     ($ :input {:id           id
                :type         "password"
                :class        "input input-sm w-full"
                :autoComplete auto-complete
                :value        value
                :on-change    #(on-change (.. % -target -value))})
     (when hint ($ :p {:class "text-xs text-error"} hint))))

(defui ^:private password-form
  "Change the password. Shows the result of its own save only: the
   `submitted?` latch ignores an earlier save by another form."
  [{:keys [email on-close]}]
  (let [save                        (uix.rf/use-subscribe [:account/update])
        update-error                (uix.rf/use-subscribe [:account/update-error])
        [current set-current!]      (use-state "")
        [new-pw set-new-pw!]        (use-state "")
        [confirm set-confirm!]      (use-state "")
        [submitted? set-submitted!] (use-state false)
        mine?                       (and submitted? (= :password (:form save)))
        pending?                    (and mine? (= :pending (:status save)))]
    (if (and mine? (= :saved (:status save)))
      ($ :div {:class "flex flex-col gap-2 text-sm"}
         ($ :p "Your password has been changed. Your other devices have been signed out, "
            "and we've emailed you to confirm the change.")
         ($ :div {:class "modal-action mt-4"}
            ($ :button {:type "button" :class "btn btn-sm btn-primary flex-1" :on-click on-close}
               "Done")))
      ($ :form {:class     "flex flex-col gap-3"
                :on-submit (fn [^js e]
                             (.preventDefault e)
                             (set-submitted! true)
                             (rf/dispatch [:account/update {:current_password      current
                                                            :password              new-pw
                                                            :password_confirmation confirm}
                                           :password]))}
         ;; Lets password managers file the new password under this account.
         ($ :input {:type         "text"
                    :class        "hidden"
                    :aria-hidden  true
                    :tab-index    -1
                    :autoComplete "username"
                    :value        (or email "")
                    :read-only    true})
         ($ password-field {:id            "current-password"
                            :label         "Current password"
                            :auto-complete "current-password"
                            :value         current
                            :on-change     set-current!})
         ($ password-field {:id            "new-password"
                            :label         "New password"
                            :auto-complete "new-password"
                            :value         new-pw
                            :on-change     set-new-pw!})
         ($ password-field {:id            "confirm-password"
                            :label         "Confirm new password"
                            :auto-complete "new-password"
                            :value         confirm
                            :on-change     set-confirm!
                            :hint          (when (and (seq confirm) (not= confirm new-pw))
                                             "The passwords don't match.")})
         (when (and mine? (= :failed (:status save)))
           ($ :p {:class "text-sm text-error"} update-error))
         ($ :p {:class "text-xs text-gray-500"}
            "Your other devices will be signed out. We'll email you to confirm the change.")
         ($ :div {:class "modal-action flex gap-2 mt-2"}
            ($ :button {:type "button" :class "btn btn-sm flex-1" :on-click on-close} "Cancel")
            ($ :button {:type     "submit"
                        :class    "btn btn-sm btn-primary flex-1"
                        :disabled (or pending? (str/blank? current) (str/blank? new-pw)
                                      (not= new-pw confirm))}
               (when pending? ($ :span {:class "loading loading-spinner loading-xs"}))
               "Change password"))))))

;;; Billing

(defui ^:private badge [{:keys [badge]}]
  (when-let [[tone label] badge]
    ($ :span {:class (str "badge badge-sm badge-soft "
                          (case tone
                            :success "badge-success"
                            :warning "badge-warning"
                            "badge-neutral"))}
       label)))

(defui ^:private billing-button
  [{:keys [label target primary? pending]}]
  ($ :button {:class    (str "btn btn-sm" (when primary? " btn-primary"))
              :disabled (some? pending)
              :on-click #(rf/dispatch [:billing/start target])}
     (when (= target pending)
       ($ :span {:class "loading loading-spinner loading-xs"}))
     label))

(defui ^:private billing-rows
  "The Billing frame's rows for the view-model `view` (see
   `account-view/billing-view`)."
  [{:keys [view thanks? pending]}]
  (let [{:keys [action cta status]} view]
    (case action
      :loading
      ($ :p {:class "px-4 py-3 text-sm text-gray-400"} "Checking…")

      (:manage :cancelling)
      ($ :<>
         (when thanks?
           ($ :p {:class "flex items-center gap-2 px-4 py-3 text-sm text-success"}
              ($ CircleCheck {:class "h-4 w-4 shrink-0"})
              "Thank you for subscribing!"))
         ($ row {:label "Plan" :muted? true}
            (:plan view)
            ($ badge {:badge (:badge view)}))
         (if (= :cancelling action)
           (when (:ends-on view) ($ row {:label "Ends on" :muted? true} (:ends-on view)))
           (when (:next-payment view) ($ row {:label "Next payment" :muted? true} (:next-payment view))))
         ($ row {:label "Change plan, update your card, or cancel." :muted? true}
            ($ billing-button {:label "Manage subscription" :target :portal :pending pending})))

      :activating
      ($ :div {:class "flex items-center gap-3 px-4 py-4 text-sm"}
         ($ :span {:class "loading loading-spinner loading-sm"})
         "Finalising your subscription…")

      ($ :<>
         (when status
           ($ row {:label (:detail status) :muted? true}
              ($ badge {:badge (:badge status)})))
         (when-not (= :none action)
           (for [{:keys [plan title price cadence features primary?]} c/subscription-plans]
             ($ row {:key   (name plan)
                     :label title
                     ;; Every plan has all features; show only what sets it apart.
                     :hint  (str/join " · " (cons (str price cadence)
                                                  (remove #{"All features"} features)))}
                ($ billing-button {:label    cta
                                   :target   plan
                                   :primary? primary?
                                   :pending  pending}))))))))

(defui ^:private group-note []
  ($ :p "Individual plans are valid for a single practitioner. For a group practice, please "
     ($ :a {:class "link"
            :href  (str "mailto:" c/support-email "?subject="
                        (js/encodeURIComponent "Group practice billing"))}
        "contact us")
     "."))

;;; Page

(defui account []
  (let [user                                    (uix.rf/use-subscribe [:auth/user])
        standing                                (:standing user)
        display-name                            (:display_name user)
        save                                    (uix.rf/use-subscribe [:account/update])
        update-error                            (uix.rf/use-subscribe [:account/update-error])
        billing-error                           (uix.rf/use-subscribe [:account/billing-error])
        billing-pending                         (uix.rf/use-subscribe [:account/billing-pending])
        query-params                            (uix.rf/use-subscribe [:router/query-params])
        [checkout-thanks? set-checkout-thanks!] (use-state false)
        [poll-count set-poll-count!]            (use-state 0)
        [sheet set-sheet!]                      (use-state nil)
        {:keys [action] :as billing}            (account-view/billing-view
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
    ;; re-ask the server so the section flips to the real subscribed state
    ;; on its own — no reload, ever: quick polls for the first ~30s (the
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
                "This page is still in development. To change your email address, "
                "for any billing inquiries, or to close your account and delete your "
                "information, please email us at "
                ($ :a {:href (str "mailto:" c/support-email)}
                   c/support-email)
                "."))

          ($ :h1 {:class "text-lg font-bold mb-6"} "Account")

          ($ section {:title "Profile"
                      :note  (when (and update-error (= :display-name (:form save)))
                               ($ :span {:class "text-error"} update-error))}
             ($ row {:label "Display name" :for "display-name"}
                ($ :form {:class     "flex gap-2"
                          :on-submit (fn [^js e]
                                       (.preventDefault e)
                                       (when commit
                                         (rf/dispatch [:account/update {:display_name commit}
                                                       :display-name])))}
                   ($ :input {:id       "display-name"
                              :class    "input input-sm w-full max-w-64"
                              :type     "text"
                              :value    draft
                              :disabled (nil? user)
                              :onChange #(set-draft! (.. % -target -value))})
                   ($ :button {:type     "submit"
                               :class    "btn btn-sm shrink-0"
                               :disabled (nil? commit)}
                      "Save"))))

          ($ section {:title "Sign-in"}
             ($ row {:label "Email" :hint (or (:email user) "…")})
             ($ row {:label "Password" :hint "••••••••"}
                ($ :button {:class    "btn btn-sm"
                            :disabled (nil? user)
                            :on-click #(set-sheet! :password)}
                   "Change…")))

          ($ section {:title "Email preferences"
                      :note  (when (and update-error (= :product-updates (:form save)))
                               ($ :span {:class "text-error"} update-error))}
             ($ row {:label "Product updates"
                     :for   "product-updates"
                     :hint  "You always get service notices about your account, such as changes to the Terms."}
                ($ :input
                   {:id        "product-updates"
                    :type      "checkbox"
                    :class     "toggle toggle-sm"
                    :checked   (and (some? (:product_updates_chosen_at user))
                                    (nil? (:product_updates_opted_out_at user)))
                    :disabled  (nil? user)
                    :on-change #(rf/dispatch [:account/update
                                              {:product_updates (.. % -target -checked)}
                                              :product-updates])})))

          ($ section {:title "Billing"
                      :above (when (#{:subscribe :resubscribe} action)
                               ($ banner {:variant :info :class "mb-2"}
                                  ($ :p "A subscription is not required to use Parts while in beta. "
                                     "Subscribing now supports continued development.")))
                      :note  ($ :<>
                                (when billing-error
                                  ($ :p {:class "text-error mb-1"} billing-error))
                                ($ group-note))}
             ($ billing-rows {:view    billing
                              :thanks? checkout-thanks?
                              :pending billing-pending}))

          ($ app-footer))

       ($ modal {:show     (= :password sheet)
                 :title    "Change password"
                 :on-close #(set-sheet! nil)}
          ;; Mounted only while open, so each opening starts empty.
          (when (= :password sheet)
            ($ password-form {:email (:email user) :on-close #(set-sheet! nil)}))))))
