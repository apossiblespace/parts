(ns parts.frontend.state.fx
  (:require
   [cljs.core.async :refer [<! go]]
   [parts.frontend.api.core :as api]
   [parts.frontend.api.queue :as queue]
   [re-frame.core :as rf]
   [parts.frontend.api.utils :as utils]))

(rf/reg-fx
 :queue/add-event
 (fn [event]
   (queue/add-events!
    (:entity event)
    [event])))

;; NOTE: `credentials` can have a callback key, which can hold a function that
;; will be called, if provided, with the API response.
(rf/reg-fx
 :auth/login-fx
 (fn [credentials]
   (go
     (let [resp (<! (api/login {:email (:email credentials)
                                :password (:password credentials)}))]
       (rf/dispatch [:auth/set-loading false])
       (when (= 200 (:status resp))
         (rf/dispatch [:auth/check-auth]))
       (when (:callback credentials)
         ((:callback credentials) resp))))))

(rf/reg-fx
 :auth/logout-fx
 (fn [_]
   (api/logout)))

(rf/reg-fx
 :auth/check-auth-fx
 (fn [_]
   (go
     (let [has-token (utils/get-tokens)]
       (rf/dispatch [:auth/set-loading (boolean has-token)])
       (when has-token
         (let [resp (<! (api/get-current-user))]
           (rf/dispatch [:auth/set-loading false])
           (when (= 200 (:status resp))
             (rf/dispatch [:auth/set-user (:body resp)]))))))))
