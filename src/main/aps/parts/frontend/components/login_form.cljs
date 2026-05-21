(ns aps.parts.frontend.components.login-form
  "Email/password login form, embeddable anywhere — currently the
   full-page auth screen.

   `on-success` is called with the API result after a successful login;
   when omitted the form relies on the surrounding auth flow."
  (:require
   [aps.parts.frontend.api.utils :as utils]
   [re-frame.core :as rf]
   [uix.core :refer [defui $ use-state]]))

(defn- email-from-query
  "The `email` query param, if the page was reached with one — an invite
   redemption redirects to /app/login?email=... so the invited member
   doesn't have to retype the address they were invited at. Empty string
   when absent."
  []
  (or (-> js/window .-location .-search
          js/URLSearchParams.
          (.get "email"))
      ""))

(defui login-form [{:keys [on-success]}]
  (let [[email set-email]       (use-state (email-from-query))
        [password set-password] (use-state "")
        [error set-error]       (use-state nil)
        [loading set-loading]   (use-state false)

        handle-submit           (fn [e]
                                  (.preventDefault e)
                                  (set-loading true)
                                  (set-error nil)
                                  (rf/dispatch [:auth/login
                                                {:email    email
                                                 :password password
                                                 :callback (fn [result]
                                                             (set-loading false)
                                                             (if (= 401 (:status result))
                                                               (set-error (get-in result [:body :error]))
                                                               (when on-success
                                                                 (on-success result))))}]))]

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
                ($ :label {:class "fieldset-label" :for "email"}
                   "Email")
                ($ :input
                   {:type        "email"
                    :id          "email"
                    :placeholder "self@you.com"
                    :class       "input input-sm w-full"
                    :value       email
                    :disabled    loading
                    :on-change   #(set-email (.. % -target -value))
                    :required    true}))

             ($ :div {:class "form-control"}
                ($ :label {:class "fieldset-label" :for "password"}
                   "Password")
                ($ :input
                   {:type      "password"
                    :id        "password"
                    :class     "input input-sm w-full"
                    :value     password
                    :disabled  loading
                    :on-change #(set-password (.. % -target -value))
                    :required  true}))
             ($ :div {:class "modal-action mt-4"}
                ($ :button
                   {:type     "submit"
                    :disabled loading
                    :class    "btn btn-sm btn-primary w-full"}
                   (if loading
                     ($ :<>
                        ($ :span {:class "loading loading-spinner"})
                        "Logging in...")
                     "Log in"))))))))
