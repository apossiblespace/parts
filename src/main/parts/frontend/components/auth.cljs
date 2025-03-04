(ns parts.frontend.components.auth
  (:require
   [uix.core :refer [defui $ use-state use-effect use-callback]]
   [cljs.core.async :refer [<!]]
   [parts.frontend.context :as ctx]
   [parts.frontend.utils.api :as api]
   [parts.frontend.components.modal :refer [modal]])
  (:require-macros
   [cljs.core.async.macros :refer [go]]))

(defui auth-provider [{:keys [children]}]
  (let [[auth-state set-auth-state] (use-state
                                     {:logged-in false
                                      :email nil
                                      :loading true})

        login (use-callback
               (fn [email password csrf-token]
                 (let [login-promise (js/Promise.
                                      (fn [resolve reject]
                                        (go
                                          (let [response (<! (api/login email password csrf-token))
                                                _ (js/console.log "Login response:", response)
                                                {:keys [success data error]} response]
                                            (if success
                                              (do
                                                (js/console.log "Login success, saving tokens:", data)
                                                (api/save-tokens data)
                                                (api/save-user-email email)
                                                (set-auth-state
                                                 {:logged-in true
                                                  :email email
                                                  :loading false})
                                                (resolve true))
                                              (do
                                                (js/console.log "Login failed:", error)
                                                (reject (or error "Login failed"))))))))]
                   login-promise))
               [])

        logout (use-callback
                (fn []
                  (let [logout-promise (js/Promise.
                                        (fn [resolve _reject]
                                          (go
                                            (let [_ (<! (api/logout))]
                                              (set-auth-state
                                               {:logged-in false
                                                :email nil
                                                :loading false})
                                              (resolve true)))))]
                    logout-promise))
                [])]

    ;; Load auth state on mount
    (use-effect
     (fn []
       (let [effect-fn
             (fn []
               (go
                 (if-let [tokens (api/get-tokens)]
                   (let [stored-email (api/get-user-email)]
                     (js/console.log "Found tokens in storage:", tokens)
                     (js/console.log "Found email in storage:", stored-email)

                     ;; FIXME: This should be more of a user info map rather
                     ;; than just an email string.
                     (if stored-email
                       ;; We have the email stored, just use it
                       (do
                         (js/console.log "Using stored email:", stored-email)
                         (set-auth-state
                          {:logged-in true
                           :email stored-email
                           :loading false}))

                       ;; No email stored, try to get user info
                       (let [response (<! (api/get-user-info))
                             _ (js/console.log "Get user info response:", response)
                             {:keys [success data]} response]
                         (if success
                           (do
                             (js/console.log "Successfully got user info:", data)
                             (when-let [email (:email data)]
                               (api/save-user-email email))
                             (set-auth-state
                              {:logged-in true
                               :email (:email data)
                               :loading false}))
                           (do
                             (js/console.log "Failed to get user info with token")
                             ;; Failed to get user info with existing token
                             (api/clear-tokens)
                             (set-auth-state
                              {:logged-in false
                               :email nil
                               :loading false}))))))
                   (do
                     (js/console.log "No tokens found in storage")
                     ;; No tokens found
                     (set-auth-state
                      {:logged-in false
                       :email nil
                       :loading false})))))]
         (effect-fn))
       nil)
     [])

    ($ (.-Provider ctx/auth-context)
       {:value (merge auth-state
                      {:login login
                       :logout logout})}
       children)))

(defui login-modal [{:keys [show on-close]}]
  (let [[email set-email] (use-state "")
        [password set-password] (use-state "")
        [error set-error] (use-state nil)
        [loading set-loading] (use-state false)
        {:keys [login]} (uix.core/use-context ctx/auth-context)
        csrf-token (or (api/get-csrf-token) "")

        handle-submit (fn [e]
                        (.preventDefault e)
                        (set-loading true)
                        (set-error nil)
                        (.then
                         (login email password csrf-token)
                         (fn [_]
                           (set-loading false)
                           (on-close))
                         (fn [err]
                           (js/console.log "Login error:", err)
                           (set-loading false)
                           (set-error "Invalid email or password"))))]

    ($ modal
       {:show show
        :title "Log in"
        :on-close on-close}

       ($ :form {:on-submit handle-submit}
          (when error
            ($ :div {:class "error-message"}
               error))

          ;; Anti-forgery token - hidden field
          (when-let [token (api/get-csrf-token)]
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
