(ns aps.parts.frontend.components.signup-form
  "Email/display-name/password signup form, embeddable in the auth screen.

   Dispatches `:auth/register`; on a 201 the `:auth/register-fx` effect
   dispatches `:auth/check-auth`, which refreshes `:auth/user` and flips
   the SPA root from the auth screen into the app — the same gate-in-place
   landing as login. `on-success`, when supplied, is called with the API
   result after a successful registration."
  (:require
   [aps.parts.frontend.api.utils :as utils]
   [re-frame.core :as rf]
   [uix.core :refer [defui $ use-state]]))

(defui signup-form [{:keys [on-success]}]
  (let [[email set-email]                       (use-state "")
        [display-name set-display-name]         (use-state "")
        [password set-password]                 (use-state "")
        [password-confirm set-password-confirm] (use-state "")
        [error set-error]                       (use-state nil)
        [loading set-loading]                   (use-state false)

        handle-submit
        (fn [e]
          (.preventDefault e)
          (if (not= password password-confirm)
            (set-error "Passwords do not match")
            (do
              (set-loading true)
              (set-error nil)
              (rf/dispatch
               [:auth/register
                {:email                 email
                 :display_name          display-name
                 :password              password
                 :password_confirmation password-confirm
                 :callback
                 (fn [result]
                   (set-loading false)
                   (if (= 201 (:status result))
                     (when on-success (on-success result))
                     (set-error (or (get-in result [:body :error])
                                    "Could not create your account"))))}]))))]

    ($ :<>
       (when error
         ($ :div {:class "alert alert-error mb-4"}
            ($ :span {:class "font-medium"} error)))

       ($ :form {:on-submit handle-submit}

          (when-let [csrf-token (utils/get-csrf-token)]
            ($ :input
               {:type  "hidden"
                :id    "__anti-forgery-token"
                :name  "__anti-forgery-token"
                :value csrf-token}))

          ($ :fieldset
             {:class "fieldset w-full"}
             ($ :div {:class "form-control"}
                ($ :label {:class "fieldset-label" :for "signup-email"}
                   "Email")
                ($ :input
                   {:type        "email"
                    :id          "signup-email"
                    :placeholder "self@you.com"
                    :class       "input input-sm w-full"
                    :value       email
                    :disabled    loading
                    :on-change   #(set-email (.. % -target -value))
                    :required    true}))

             ($ :div {:class "form-control"}
                ($ :label {:class "fieldset-label" :for "signup-display-name"}
                   "Display name")
                ($ :input
                   {:type        "text"
                    :id          "signup-display-name"
                    :placeholder "How your name appears in Parts"
                    :class       "input input-sm w-full"
                    :value       display-name
                    :disabled    loading
                    :on-change   #(set-display-name (.. % -target -value))
                    :required    true}))

             ($ :div {:class "form-control"}
                ($ :label {:class "fieldset-label" :for "signup-password"}
                   "Password")
                ($ :input
                   {:type      "password"
                    :id        "signup-password"
                    :class     "input input-sm w-full"
                    :value     password
                    :disabled  loading
                    :on-change #(set-password (.. % -target -value))
                    :required  true}))

             ($ :div {:class "form-control"}
                ($ :label {:class "fieldset-label" :for "signup-password-confirm"}
                   "Confirm password")
                ($ :input
                   {:type      "password"
                    :id        "signup-password-confirm"
                    :class     "input input-sm w-full"
                    :value     password-confirm
                    :disabled  loading
                    :on-change #(set-password-confirm (.. % -target -value))
                    :required  true}))

             ($ :div {:class "modal-action mt-4"}
                ($ :button
                   {:type     "submit"
                    :disabled loading
                    :class    "btn btn-sm btn-primary w-full"}
                   (if loading
                     ($ :<>
                        ($ :span {:class "loading loading-spinner"})
                        "Creating account...")
                     "Create account"))))))))
