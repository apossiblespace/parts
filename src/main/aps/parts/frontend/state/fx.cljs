(ns aps.parts.frontend.state.fx
  (:require
   [aps.parts.frontend.api.core :as api]
   [aps.parts.frontend.api.queue :as queue]
   [aps.parts.frontend.api.utils :as utils]
   [aps.parts.frontend.storage.protocol :refer [list-maps load-map create-map update-map]]
   [aps.parts.frontend.storage.registry :as storage-registry]
   [cljs.core.async :refer [<! go]]
   [re-frame.core :as rf]))

(rf/reg-fx
 :queue/add-event
 (fn [event]
   (queue/add-events! [event])))

(rf/reg-fx
 :api-utils/save-current-map-id
 (fn [map-id]
   (utils/save-current-map-id map-id)))

;; NOTE: the params can have a callback key, which can hold a function that will
;; be called, if provided, with the API response.
(rf/reg-fx
 :auth/login-fx
 (fn [{:keys [email password callback]}]
   (go
     (let [resp (<! (api/login {:email email :password password}))]
       (rf/dispatch [:auth/set-loading false])
       (when (= 200 (:status resp))
         ;; Clear playground data when existing user logs in
         (utils/clear-playground-data)
         ;; Login sets the session cookie; the response body is the user.
         (rf/dispatch [:auth/set-user (:body resp)]))
       (when callback
         (callback resp))))))

(rf/reg-fx
 :auth/register-fx
 (fn [{:keys [email username display_name password password_confirmation callback]}]
   (go
     (let [resp (<! (api/register {:email                 email
                                   :username              username
                                   :display_name          display_name
                                   :password              password
                                   :password_confirmation password_confirmation}))]
       (when (= 201 (:status resp))
         ;; Register sets the session cookie; the response body is the account.
         (rf/dispatch [:auth/set-user (:body resp)]))
       (when callback
         (callback resp))))))

(rf/reg-fx
 :auth/logout-fx
 (fn [_]
   (go
     ;; Await the logout POST so the session-clearing cookie is processed
     ;; before navigating away.
     (<! (api/logout))
     (.replace (.-location js/window) "/"))))

(rf/reg-fx
 :auth/check-auth-fx
 (fn [_]
   (go
     ;; No token to inspect — just ask the server. A valid session cookie
     ;; rides the request automatically; 200 means signed in.
     (let [resp (<! (api/get-current-user))]
       (rf/dispatch [:auth/set-loading false])
       (when (= 200 (:status resp))
         (rf/dispatch [:auth/set-user (:body resp)]))))))

(rf/reg-fx
 :storage/get-map
 (fn [{:keys [id]}]
   (go
     (if-let [backend (storage-registry/get-backend)]
       (let [result (<! (load-map backend id))]
         (cond
           ;; Success - got a map with an :id
           (:id result)
           (rf/dispatch [:map/fetch-success result])

           ;; Error responses from http-backend
           (= :unauthorized (:error result))
           (rf/dispatch [:map/fetch-unauthorized])

           (= :forbidden (:error result))
           (rf/dispatch [:map/fetch-forbidden])

           (= :not-found (:error result))
           (rf/dispatch [:map/fetch-not-found])

           ;; Any other error or nil
           :else
           (rf/dispatch [:map/fetch-failure "Failed to load map"])))
       (rf/dispatch [:map/fetch-failure "No storage backend available"])))))

(rf/reg-fx
 :storage/create-map
 (fn [params]
   (go
     (if-let [backend (storage-registry/get-backend)]
       (let [the-map (<! (create-map backend params))]
         (if the-map
           (rf/dispatch [:map/create-success the-map])
           (rf/dispatch [:map/create-failure "Failed to create map"])))
       (rf/dispatch [:map/create-failure "No storage backend available"])))))

(rf/reg-fx
 :storage/get-maps
 (fn [_]
   (go
     (if-let [backend (storage-registry/get-backend)]
       (let [maps (<! (list-maps backend))]
         (if maps
           (rf/dispatch [:map/fetch-list-success maps])
           (rf/dispatch [:map/fetch-list-failure "Failed to load maps"])))
       (rf/dispatch [:map/fetch-list-failure "No storage backend available"])))))

(rf/reg-fx
 :storage/update-map
 (fn [{:keys [id map-data]}]
   (go
     (if-let [backend (storage-registry/get-backend)]
       (let [updated-map (<! (update-map backend id map-data))]
         (if updated-map
           (rf/dispatch [:map/update-success updated-map])
           (rf/dispatch [:map/update-failure "Failed to update map"])))
       (rf/dispatch [:map/update-failure "No storage backend available"])))))
