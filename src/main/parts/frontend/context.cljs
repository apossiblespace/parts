(ns parts.frontend.context
  (:require
   [clojure.core.async :refer [<!]]
   [parts.frontend.api.core :as api]
   [parts.frontend.api.utils :as utils]
   [uix.core :refer [$ defui use-state use-effect use-context]])
  (:require-macros
   [cljs.core.async.macros :refer [go]]))

(def update-node-context (uix.core/create-context nil))

(def auth-context (uix.core/create-context
                   {:logged-in false
                    :email nil
                    :login nil
                    :logout nil}))

(defui auth-provider [{:keys [children]}]
  (let [[user set-user] (use-state nil)
        [loading set-loading] (use-state true)
        fetch-user! (fn []
                      (go
                        (let [resp (<! (api/get-current-user))]
                          (set-loading false)
                          (when (= 200 (:status resp))
                            (set-user (:body resp))))))
        login! (fn [creds]
                 (go
                   (let [resp (<! (api/login creds))]
                     (when (= 200 (:status resp))
                       (<! (fetch-user!)))
                     resp)))
        logout! (fn []
                  (api/logout)
                  (set-user nil))
        value {:user user
               :loading loading
               :login login!
               :logout logout!}]

    (use-effect
     (fn []
       (println "[auth-provider] checking for token")
       (if (utils/get-tokens)
         (do
           (println "[auth-provider] token found, already signed in")
           (fetch-user!))
         (set-loading false)))
     [fetch-user!])

    ($ auth-context.Provider {:value value}
       children)))

(defn use-auth []
  (let [auth (use-context auth-context)]
    (when (nil? auth)
      (js/console.error "use-auth must be used within an AuthProvider"))
    auth))
