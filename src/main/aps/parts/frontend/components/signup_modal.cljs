(ns aps.parts.frontend.components.signup-modal
  (:require
   [aps.parts.frontend.api.utils :as utils]
   [aps.parts.frontend.components.modal :refer [modal]]
   [re-frame.core :as rf]
   [uix.core :refer [defui $ use-state]]))

(defui signup-modal [{:keys [show on-close on-success]}]
  (let [[email set-email]                       (use-state "")
        [username set-username]                 (use-state "")
        [password set-password]                 (use-state "")
        [password-confirm set-password-confirm] (use-state "")
        [error set-error]                       (use-state nil)
        [loading set-loading]                   (use-state false)

        handle-close                            (fn []
                                                  (on-close)
                                                  (set-loading false)
                                                  (set-error nil)
                                                  (set-email "")
                                                  (set-username "")
                                                  (set-password "")
                                                  (set-password-confirm ""))

        handle-submit                           (fn [e]
                                                  (.preventDefault e)
                                                  (if (not= password password-confirm)
                                                    (set-error "Passwords do not match")
                                                    (do
                                                      (set-loading true)
                                                      (set-error nil)
                                                      (rf/dispatch
                                                       [:auth/register
                                                        {:email                 email
                                                         :username              username
                                                         :display_name          username
                                                         :password              password
                                                         :password_confirmation password-confirm
                                                         :callback              (fn [result]
                                                                                  (set-loading false)
                                                                                  (if (= 201 (:status result))
                                                                                    (do
                                                                                      (handle-close)
                                                                                      (when on-success
                                                                                        (on-success result)))
                                                                                    (set-error (or (get-in result [:body :error])
                                                                                                   "Registration failed"))))}]))))]

    ($ modal
       {:show     show
        :on-close handle-close
        :title    "Create an account"}

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
                ($ :div {:class "form-control mb-3"}
                   ($ :label {:class "fieldset-label" :for "signup-email"}
                      "Email")
                   ($ :input
                      {:type        "email"
                       :id          "signup-email"
                       :placeholder "self@you.com"
                       :class       "input w-full"
                       :value       email
                       :disabled    loading
                       :on-change   #(set-email (.. % -target -value))
                       :required    true}))

                ($ :div {:class "form-control mb-3"}
                   ($ :label {:class "fieldset-label" :for "signup-username"}
                      "Username")
                   ($ :input
                      {:type        "text"
                       :id          "signup-username"
                       :placeholder "yourname"
                       :class       "input w-full"
                       :value       username
                       :disabled    loading
                       :on-change   #(set-username (.. % -target -value))
                       :required    true}))

                ($ :div {:class "form-control mb-3"}
                   ($ :label {:class "fieldset-label" :for "signup-password"}
                      "Password")
                   ($ :input
                      {:type      "password"
                       :id        "signup-password"
                       :class     "input w-full"
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
                       :class     "input w-full"
                       :value     password-confirm
                       :disabled  loading
                       :on-change #(set-password-confirm (.. % -target -value))
                       :required  true}))

                ($ :div {:class "modal-action mt-6 space-x-2 flex"}
                   ($ :button
                      {:type     "button"
                       :class    "btn flex-1"
                       :disabled loading
                       :on-click on-close}
                      "Cancel")
                   ($ :button
                      {:type     "submit"
                       :disabled loading
                       :class    "btn btn-primary flex-1"}
                      (if loading
                        ($ :<>
                           ($ :span {:class "loading loading-spinner"})
                           "Creating account...")
                        "Create account")))))))))
