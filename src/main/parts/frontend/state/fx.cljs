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

;; NOTE: the params can have a callback key, which can hold a function that will
;; be called, if provided, with the API response.
(rf/reg-fx
 :auth/login-fx
 (fn [{:keys [email password callback]}]
   (go
     (let [resp (<! (api/login {:email email :password password}))]
       (rf/dispatch [:auth/set-loading false])
       (when (= 200 (:status resp))
         (rf/dispatch [:auth/check-auth]))
       (when callback
         (callback resp))))))

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

(rf/reg-fx
 :api/get-system
 (fn [{:keys [id]}]
   (go
     (let [response (<! (api/get-system id))]
       (if (= 200 (:status response))
         (rf/dispatch [:system/fetch-success (:body response)])
         (rf/dispatch [:system/fetch-failure (:body response)]))))))

(rf/reg-fx
 :api/create-system
 (fn [params]
   (go
     (let [response (<! (api/create-system params))]
       (if (= 201 (:status response))
         (rf/dispatch [:system/create-success (:body response)])
         (rf/dispatch [:system/create-failure (:body response)]))))))

(rf/reg-fx
 :api/get-systems
 (fn [_]
   (go
     (let [response (<! (api/get-systems))]
       (if (= 200 (:status response))
         (rf/dispatch [:system/fetch-list-success (:body response)])
         (rf/dispatch [:system/fetch-list-failure (:body response)]))))))
