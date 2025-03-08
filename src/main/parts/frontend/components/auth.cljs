(ns parts.frontend.components.auth
  (:require
   [uix.core :refer [defui $ use-state use-effect use-callback]]
   [cljs.core.async :refer [<! chan]]
   [parts.frontend.context :as ctx]
   [parts.frontend.api.core :as api]
   [parts.frontend.utils.api :as utils] ;; Still need for token storage functions
   [parts.frontend.components.modal :refer [modal]])
  (:require-macros
   [cljs.core.async.macros :refer [go]]))

;; Auth-specific API operations using core API functions
(defn login-user [email password csrf-token]
  (let [result-chan (chan)]
    ;; Create the request
    (api/create-entity "/auth/login" 
                      {:email email
                       :password password
                       :__anti-forgery-token csrf-token})
    
    ;; Wait for the response on the event channel
    (go
      (loop []
        (let [event (<! api/event-channel)]
          (cond
            ;; Success event for our login
            (and (= (:event event) :api-success)
                 (= (:type event) :create-entity))
            (do
              (js/console.log "Got login success event" event)
              (put! result-chan {:success true :data (:data event)}))
            
            ;; Error event for our login
            (and (= (:event event) :api-error)
                 (= (:type (:error event)) :create-entity))
            (do
              (js/console.log "Got login error event" event)
              (put! result-chan {:success false :error (:message (:error event))}))
            
            ;; Neither success nor error yet, keep waiting
            :else
            (recur)))))
    
    result-chan))

(defn logout-user []
  (let [result-chan (chan)
        tokens (utils/get-tokens)]
    
    (when tokens
      ;; Create the logout request
      (api/create-entity "/auth/logout" 
                        {:refresh_token (:refresh_token tokens)
                         :__anti-forgery-token (utils/get-csrf-token)})
      
      ;; Wait for response on event channel
      (go
        (loop []
          (let [event (<! api/event-channel)]
            (when (or (and (= (:event event) :api-success)
                          (= (:type event) :create-entity))
                     (and (= (:event event) :api-error)
                          (= (:type (:error event)) :create-entity)))
              ;; Success or error, either way we're logged out
              (put! result-chan {:success true}))
            
            ;; Keep waiting
            (recur)))))
    
    ;; If no tokens, just return success
    (when-not tokens
      (put! result-chan {:success true}))
    
    result-chan))

(defn get-user-info []
  (let [result-chan (chan)]
    (go
      (let [user-data (<! (api/fetch-data "/account"))]
        (put! result-chan user-data)))
    result-chan))

(defui auth-provider [{:keys [children]}]
  (let [[auth-state set-auth-state] (use-state
                                     {:logged-in false
                                      :email nil
                                      :loading true})

        login (use-callback
               (fn [email password csrf-token]
                 (let [result-chan (chan)]
                   (go
                     (let [response (<! (login-user email password csrf-token))]
                       (js/console.log "Login response:", response)
                       (if (:success response)
                         (let [data (:data response)]
                           (js/console.log "Login success, saving tokens:", data)
                           (utils/save-tokens data)
                           (utils/save-user-email email)
                           (set-auth-state
                            {:logged-in true
                             :email email
                             :loading false})
                           (put! result-chan true))
                         (put! result-chan (js/Error. "Login failed")))))
                   result-chan))
               [])

        logout (use-callback
                (fn []
                  (let [result-chan (chan)]
                    (go
                      (let [_ (<! (logout-user))]
                        (utils/clear-tokens)
                        (set-auth-state
                         {:logged-in false
                          :email nil
                          :loading false})
                        (put! result-chan true)))
                    result-chan))
                [])]

    ;; Load auth state on mount
    (use-effect
     (fn []
       (let [effect-fn
             (fn []
               (go
                 (if-let [tokens (utils/get-tokens)]
                   (let [stored-email (utils/get-user-email)]
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
                       (let [user-data (<! (get-user-info))]
                         (js/console.log "Get user info response:", user-data)
                         (if user-data
                           (do
                             (js/console.log "Successfully got user info:", user-data)
                             (when-let [email (:email user-data)]
                               (utils/save-user-email email))
                             (set-auth-state
                              {:logged-in true
                               :email (:email user-data)
                               :loading false}))
                           (do
                             (js/console.log "Failed to get user info with token")
                             ;; Failed to get user info with existing token
                             (utils/clear-tokens)
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
        csrf-token (or (utils/get-csrf-token) "")

        handle-submit (fn [e]
                        (.preventDefault e)
                        (set-loading true)
                        (set-error nil)
                        (go
                          (try
                            (let [result (<! (login email password csrf-token))]
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

          ;; Anti-forgery token - hidden field
          (when-let [token (utils/get-csrf-token)]
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
