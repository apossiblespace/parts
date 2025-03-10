(ns parts.frontend.components.login-modal
  (:require
   [uix.core :refer [defui $ use-state]]
   [cljs.core.async :refer [<!]]
   [parts.frontend.context :as ctx]
   [parts.frontend.utils.csrf :as csrf]
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
        :title "Log in"
        :on-close on-close}

       ($ :form {:on-submit handle-submit}
          (when error
            ($ :div {:class "error-message"}
               error))

          (when-let [token (csrf/get-token)]
            ($ :input
               {:type "hidden"
                :id "__anti-forgery-token"
                :name "__anti-forgery-token"
                :value token}))

          ($ :div {:class "form-group"}
             ($ :label {:for "email"} "Email:")
             ($ :input
                {:type "email"
                 :id "email"
                 :value email
                 :disabled loading
                 :on-change #(set-email (.. % -target -value))
                 :required true}))

          ($ :div {:class "form-group"}
             ($ :label {:for "password"} "Password:")
             ($ :input
                {:type "password"
                 :id "password"
                 :value password
                 :disabled loading
                 :on-change #(set-password (.. % -target -value))
                 :required true}))

          ($ :div {:class "form-actions"}
             ($ :button
                {:type "button"
                 :disabled loading
                 :on-click on-close}
                "Cancel")
             ($ :button
                {:type "submit"
                 :disabled loading
                 :class "primary"}
                (if loading "Logging in..." "Log in")))))))
