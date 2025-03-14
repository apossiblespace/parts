(ns parts.frontend.components.login-modal
  (:require
   [uix.core :refer [defui $ use-state]]
   [cljs.core.async :refer [<!]]
   [parts.frontend.context :as ctx]
   [parts.frontend.api.utils :as utils]
   [parts.frontend.components.modal :refer [modal]])
  (:require-macros
   [cljs.core.async.macros :refer [go]]))

(defui login-modal [{:keys [show on-close]}]
  (let [[email set-email] (use-state "")
        [password set-password] (use-state "")
        [error set-error] (use-state nil)
        [loading set-loading] (use-state false)
        {:keys [login]} (ctx/use-auth)

        handle-submit (fn [e]
                        (.preventDefault e)
                        (set-loading true)
                        (set-error nil)
                        (go
                          (try
                            (let [_result (<! (login {:email email
                                                      :password password}))]
                              (set-loading false)
                              (on-close))
                            (catch js/Error err
                              (js/console.log "Login error:", err)
                              (set-loading false)
                              (set-error "Invalid email or password")))))]

    ($ modal
       {:show show
        :on-close on-close}

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
                {:class "fieldset w-full bg-base-200 border border-base-300 p-4 rounded-box"}
                ($ :legend {:class "fieldset-legend"} "Login")

                ($ :div {:class "form-control mb-3"}
                   ($ :label {:class "fieldset-label" :for "email"}
                      "Email")
                   ($ :input
                      {:type "email"
                       :id "email"
                       :placeholder "self@you.com"
                       :class "input input-bordered w-full"
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
                       :class "input input-bordered w-full"
                       :value password
                       :disabled loading
                       :on-change #(set-password (.. % -target -value))
                       :required true}))
                ($ :div {:class "modal-action mt-6 space-x-2 flex"}
                   ($ :button
                      {:type "button"
                       :class "btn btn-outline flex-1"
                       :disabled loading
                       :on-click on-close}
                      "Cancel")
                   ($ :button
                      {:type "submit"
                       :disabled loading
                       :class (str "btn btn-primary flex-1" (when loading "loading"))}
                      (if loading "Logging in..." "Log in")))))))))
