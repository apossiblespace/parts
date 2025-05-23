(ns parts.frontend.components.login-modal
  (:require
   [uix.core :refer [defui $ use-state]]
   [re-frame.core :as rf]
   [parts.frontend.api.utils :as utils]
   [parts.frontend.components.modal :refer [modal]]))

(defui login-modal [{:keys [show on-close]}]
  (let [[email set-email] (use-state "")
        [password set-password] (use-state "")
        [error set-error] (use-state nil)
        [loading set-loading] (use-state false)

        handle-close (fn []
                       (on-close)
                       (set-loading false)
                       (set-error nil)
                       (set-password "")
                       (set-email ""))
        handle-submit (fn [e]
                        (.preventDefault e)
                        (set-loading true)
                        (set-error nil)
                        (rf/dispatch [:auth/login
                                      {:email email
                                       :password password
                                       :callback (fn [result]
                                                   (set-loading false)
                                                   (if (= 401 (:status result))
                                                     (set-error (get-in result [:body :error]))
                                                     (handle-close)))}]))]

    ($ modal
       {:show show
        :on-close handle-close
        :title "Log in"}

       ($ :<>
          (when error
            ($ :div {:class "alert alert-error mb-4"}
               ($ :span {:class "font-medium"} error)))

          ($ :form {:on-submit handle-submit}

             (when-let [csrf-token (utils/get-csrf-token)]
               ($ :input
                  {:type "hidden"
                   :id "__anti-forgery-token"
                   :name "__anti-forgery-token"
                   :value csrf-token}))

             ($ :fieldset
                {:class "fieldset w-full"}
                ($ :div {:class "form-control mb-3"}
                   ($ :label {:class "fieldset-label" :for "email"}
                      "Email")
                   ($ :input
                      {:type "email"
                       :id "email"
                       :placeholder "self@you.com"
                       :class "input w-full"
                       :value email
                       :disabled loading
                       :on-change #(set-email (.. % -target -value))
                       :required true}))

                ($ :div {:class "form-control"}
                   ($ :label {:class "fieldset-label" :for "password"}
                      "Password")
                   ($ :input
                      {:type "password"
                       :id "password"
                       :class "input w-full"
                       :value password
                       :disabled loading
                       :on-change #(set-password (.. % -target -value))
                       :required true}))
                ($ :div {:class "modal-action mt-6 space-x-2 flex"}
                   ($ :button
                      {:type "button"
                       :class "btn flex-1"
                       :disabled loading
                       :on-click on-close}
                      "Cancel")
                   ($ :button
                      {:type "submit"
                       :disabled loading
                       :class "btn btn-primary flex-1"}
                      (if loading
                        ($ :<>
                           ($ :span {:class "loading loading-spinner"})
                           "Logging in...")
                        "Log in")))))))))
