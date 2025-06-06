(ns parts.frontend.state.fx
  (:require
   [cljs.core.async :refer [<! go]]
   [parts.frontend.api.core :as api]
   [parts.frontend.api.queue :as queue]
   [parts.frontend.storage.registry :as storage-registry]
   [parts.frontend.storage.protocol :refer [list-systems load-system update-system]]
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
 :storage/get-system
 (fn [{:keys [id]}]
   (go
     (if-let [backend (storage-registry/get-backend)]
       (let [system (<! (load-system backend id))]
         (if system
           (rf/dispatch [:system/fetch-success system])
           (rf/dispatch [:system/fetch-failure "Failed to load system"])))
       (rf/dispatch [:system/fetch-failure "No storage backend available"])))))

(rf/reg-fx
 :storage/create-system
 (fn [params]
   (go
     (let [response (<! (api/create-system params))]
       (if (= 201 (:status response))
         (rf/dispatch [:system/create-success (:body response)])
         (rf/dispatch [:system/create-failure (:body response)]))))))

(rf/reg-fx
 :storage/get-systems
 (fn [_]
   (go
     (if-let [backend (storage-registry/get-backend)]
       (let [systems (<! (list-systems backend))]
         (if systems
           (rf/dispatch [:system/fetch-list-success systems])
           (rf/dispatch [:system/fetch-list-failure "Failed to load systems"])))
       (rf/dispatch [:system/fetch-list-failure "No storage backend available"])))))

(rf/reg-fx
 :storage/update-system
 (fn [{:keys [id system-data]}]
   (go
     (if-let [backend (storage-registry/get-backend)]
       (let [updated-system (<! (update-system backend id system-data))]
         (if updated-system
           (rf/dispatch [:system/update-success updated-system])
           (rf/dispatch [:system/update-failure "Failed to update system"])))
       (rf/dispatch [:system/update-failure "No storage backend available"])))))

;; Keep the old API effects for system creation since we don't have storage backend creation yet
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
