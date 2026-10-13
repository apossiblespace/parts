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
   [aps.parts.common.observe :as o]
   [aps.parts.common.utils :refer [normalize-email]]
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
   a frame. `above` and `note` render above and below the frame;
   `danger?` makes the title and frame red."
  [{:keys [title above note danger? children]}]
  ($ :section {:class "flex flex-col md:flex-row gap-2 md:gap-8 mb-6"}
     ($ :h2 {:class (str "text-md font-semibold shrink-0 md:w-40 md:pt-2"
                         (when danger? " text-error"))}
        title)
     ($ :div {:class "flex-1 min-w-0"}
        above
        ($ :div {:class (str "rounded-box bg-base-100 border divide-y "
                             (if danger?
                               "border-error divide-error/30"
                               "border-base-300 divide-base-300"))}
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

;;; Data download

(defn- download-error
  "What to tell the user when the ZIP request answers `status` (nil when
   the request failed without a response)."
  [status]
  (case status
    401 "Your session has ended. Please sign in again, then download your data."
    429 "Too many download attempts in the last minute. Please wait a minute and try again."
    (str "We couldn't prepare your download. Please try again, or contact us at "
         c/support-email ".")))

(defn- save-file!
  "Hands `blob` to the browser as a download named `file-name`."
  [blob file-name]
  (let [url (js/URL.createObjectURL blob)
        a   (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) file-name)
    ;; Safari starts the download later: keep the link in the page for
    ;; the click and the URL alive for a minute.
    (.appendChild js/document.body a)
    (.click a)
    (.remove a)
    (js/setTimeout #(js/URL.revokeObjectURL url) 60000)))

(defui ^:private download-data-button
  "Downloads the account ZIP. It fetches the file first, so a refused or
   failed request shows an error instead of saving the error as a ZIP."
  []
  (let [[state set-state!] (use-state nil) ;; nil, :pending, or an error message
        download!          (fn []
                             (set-state! :pending)
                             (-> (js/fetch "/api/account/export.zip" #js {:credentials "same-origin"})
                                 (.then (fn [^js response]
                                          (if (.-ok response)
                                            (.then (.blob response)
                                                   (fn [blob]
                                                     (save-file! blob
                                                                 (or (some->> (.get (.-headers response) "Content-Disposition")
                                                                              (re-find #"filename=\"([^\"]+)\"")
                                                                              second)
                                                                     "parts-data.zip"))
                                                     (o/track "Account data exported" {})
                                                     (set-state! nil)))
                                            (set-state! (download-error (.-status response))))))
                                 (.catch #(set-state! (download-error nil)))))]
    ($ :div {:class "flex flex-col items-end gap-1"}
       ($ :button {:type     "button"
                   :class    "btn btn-sm"
                   :disabled (= :pending state)
                   :on-click download!}
          (when (= :pending state)
            ($ :span {:class "loading loading-spinner loading-xs"}))
          "Download")
       (when (string? state)
         ($ :p {:class "text-xs text-error text-right max-w-64"} state)))))

;;; Sheets

(defui ^:private sheet-buttons
  "A sheet's Cancel and submit buttons. `class` styles the submit button
   (btn-primary, btn-error); it shows a spinner while `pending?`."
  [{:keys [label class pending? disabled on-cancel]}]
  ($ :div {:class "modal-action flex gap-2 mt-2"}
     ($ :button {:type "button" :class "btn btn-sm flex-1" :on-click on-cancel} "Cancel")
     ($ :button {:type     "submit"
                 :class    (str "btn btn-sm flex-1 " class)
                 :disabled (or pending? disabled)}
        (when pending? ($ :span {:class "loading loading-spinner loading-xs"}))
        label)))

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
         ($ sheet-buttons {:label     "Change password"
                           :class     "btn-primary"
                           :pending?  pending?
                           :disabled  (or (str/blank? current) (str/blank? new-pw) (not= new-pw confirm))
                           :on-cancel on-close})))))

;;; Email change

(defui ^:private email-form
  "Ask to move the account to a new address. The link goes to the new
   address; the account keeps `email` until it is used."
  [{:keys [email on-close]}]
  (let [change                     (uix.rf/use-subscribe [:account/email-change])
        [new-email set-new-email!] (use-state "")
        [password set-password!]   (use-state "")
        pending?                   (= :pending (:status change))]
    (if (= :sent (:status change))
      ($ :div {:class "flex flex-col gap-2 text-sm"}
         ($ :p "We've sent a confirmation link to " ($ :strong (:new-email change)) ".")
         ($ :p "Open it within 1 hour to finish the change. Until then, you keep signing in with "
            ($ :strong email) ".")
         ($ :div {:class "modal-action mt-4"}
            ($ :button {:type "button" :class "btn btn-sm btn-primary flex-1" :on-click on-close}
               "Done")))
      ($ :form {:class     "flex flex-col gap-3 text-sm"
                :on-submit (fn [^js e]
                             (.preventDefault e)
                             (rf/dispatch [:account/email-change {:new_email        new-email
                                                                  :current_password password}]))}
         ($ :p "We will send you a confirmation link to your new address.")
         ($ :div {:class "flex flex-col gap-1"}
            ($ :label {:class "fieldset-label" :for "new-email"} "New email address")
            ($ :input {:id           "new-email"
                       :type         "email"
                       :class        "input input-sm w-full"
                       :autoComplete "email"
                       :value        new-email
                       :on-change    #(set-new-email! (.. % -target -value))}))
         ($ password-field {:id            "email-password"
                            :label         "Current password"
                            :auto-complete "current-password"
                            :value         password
                            :on-change     set-password!})
         (when (= :failed (:status change))
           ($ :p {:class "text-sm text-error"} (:error change)))
         ($ sheet-buttons {:label     "Send link"
                           :class     "btn-primary"
                           :pending?  pending?
                           :disabled  (or (not (str/includes? new-email "@")) (str/blank? password))
                           :on-cancel on-close})))))

;;; Deletion

(defn- maps-phrase
  "What a deletion removes, for the confirmation sheet. `n` is the Map
   count, nil until the Maps list has loaded."
  [n]
  (case n
    nil "your account and all your Maps"
    1   "your account and your 1 Map"
    (str "your account and all " n " of your Maps")))

(defui ^:private delete-form
  "Confirm the account deletion with the email address and the current
   password. Offers the data download first."
  [{:keys [email subscribed? on-close]}]
  (let [maps                     (uix.rf/use-subscribe [:maps/list])
        loading?                 (uix.rf/use-subscribe [:maps/loading])
        deleting                 (uix.rf/use-subscribe [:account/delete])
        [typed set-typed!]       (use-state "")
        [password set-password!] (use-state "")
        pending?                 (= :pending (:status deleting))]
    ;; The Maps list is loaded on the Maps page; load it here for the count.
    (use-effect
     (fn []
       (rf/dispatch [:map/fetch-list])
       js/undefined)
     [])
    ($ :form {:class     "flex flex-col gap-3 text-sm"
              :on-submit (fn [^js e]
                           (.preventDefault e)
                           (rf/dispatch [:account/delete {:email            typed
                                                          :current_password password}]))}
       ($ :p "This permanently deletes "
          ($ :strong (maps-phrase (when (and (some? maps) (not loading?)) (count maps))))
          ", including archived Maps, with all their Sessions, notes and conversations.")
       ;; Only a real subscription is mentioned: naming one the User never
       ;; had would only worry them.
       (when subscribed?
         ($ :p "Your subscription is cancelled straight away, and any paid time left is lost."))
       ($ :p {:class "font-semibold"} "This cannot be undone.")
       ($ :div {:class "rounded-box bg-base-200 p-3 flex flex-wrap items-center justify-between gap-2"}
          ($ :span "Keep a copy of your data first.")
          ($ download-data-button))
       ($ :div {:class "flex flex-col gap-1"}
          ($ :label {:class "fieldset-label" :for "delete-email"}
             (str "To confirm, type " email))
          ($ :input {:id           "delete-email"
                     :type         "email"
                     :class        "input input-sm w-full"
                     :autoComplete "off"
                     :value        typed
                     :on-change    #(set-typed! (.. % -target -value))}))
       ($ password-field {:id            "delete-password"
                          :label         "Current password"
                          :auto-complete "current-password"
                          :value         password
                          :on-change     set-password!})
       (when (= :failed (:status deleting))
         ($ :p {:class "text-sm text-error"} (:error deleting)))
       ($ sheet-buttons {:label     "Delete account"
                         :class     "btn-error"
                         :pending?  pending?
                         :disabled  (or (not= (normalize-email typed) (normalize-email (str email)))
                                        (str/blank? password))
                         :on-cancel on-close}))))

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
             ($ row {:label "Email" :hint (or (:email user) "…")}
                ($ :button {:class    "btn btn-sm"
                            :disabled (nil? user)
                            :on-click (fn []
                                        ;; Sync, so the sheet's first paint has no
                                        ;; result from the last opening.
                                        (rf/dispatch-sync [:account/email-change-reset])
                                        (set-sheet! :email))}
                   "Change…"))
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

          ($ section {:title "Your data"}
             ($ row {:label "Download all your data"
                     :hint  "Download a copy of all your Maps, archived ones included, and your account details, as a ZIP file."}
                ($ download-data-button)))

          ($ :hr {:class "border-base-300 mt-8 mb-6"})
          ($ section {:title "Danger zone" :danger? true}
             ($ row {:label "Delete account"
                     :hint  "Permanently delete your account and all your Maps. This cannot be undone."}
                ($ :button {:class    "btn btn-sm btn-error"
                            :disabled (nil? user)
                            :on-click (fn []
                                        (rf/dispatch-sync [:account/delete-reset])
                                        (set-sheet! :delete))}
                   "Delete…")))

          ($ app-footer))

       ($ modal {:show     (= :password sheet)
                 :title    "Change password"
                 :on-close #(set-sheet! nil)}
          ;; Mounted only while open, so each opening starts empty.
          (when (= :password sheet)
            ($ password-form {:email (:email user) :on-close #(set-sheet! nil)})))

       ($ modal {:show     (= :email sheet)
                 :title    "Change email"
                 :on-close #(set-sheet! nil)}
          (when (= :email sheet)
            ($ email-form {:email (:email user) :on-close #(set-sheet! nil)})))

       ($ modal {:show     (= :delete sheet)
                 :title    "Delete account"
                 :on-close #(set-sheet! nil)}
          (when (= :delete sheet)
            ($ delete-form {:email       (:email user)
                            :subscribed? (get-in user [:billing :subscription_active])
                            :on-close    #(set-sheet! nil)}))))))
