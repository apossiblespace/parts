(ns aps.parts.frontend.state.fx
  (:require
   [aps.parts.frontend.api.core :as api]
   [aps.parts.frontend.api.queue :as queue]
   [aps.parts.frontend.api.utils :as utils]
   [aps.parts.frontend.storage.http-backend :as http-backend]
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
 (fn [{:keys [email display_name password password_confirmation
              accepted-medical? accepted-legal? callback]}]
   (go
     (let [resp (<! (api/register {:email                 email
                                   :display_name          display_name
                                   :password              password
                                   :password_confirmation password_confirmation
                                   :accepted-medical?     accepted-medical?
                                   :accepted-legal?       accepted-legal?}))]
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

;; -- Sessions (ADR-0014) ----------------------------------------------------
;; HTTP-only: Sessions exist for authenticated Maps; demo Maps are exempt
;; from the Session model, so these effects never fire in the playground.

(defn- error-message
  "The server's own message from an error response body, or a fallback.
   Session refusals (e.g. deleting a non-empty Session) carry the precise
   reason under :error — the UI relays it verbatim."
  [resp fallback]
  (or (get-in resp [:body :error]) fallback))

(rf/reg-fx
 :sessions/fetch-fx
 (fn [{:keys [map-id]}]
   (go
     (let [resp (<! (api/list-sessions map-id))]
       (if (= 200 (:status resp))
         (rf/dispatch [:sessions/fetch-success map-id (:body resp)])
         (rf/dispatch [:sessions/fetch-failure map-id]))))))

(rf/reg-fx
 :time-travel/fetch-fx
 (fn [{:keys [map-id session-id]}]
   (go
     (let [resp (<! (api/load-map-at map-id session-id))]
       (if (= 200 (:status resp))
         (rf/dispatch [:time-travel/snapshot-success session-id
                       ;; Same id normalization as the live map load —
                       ;; raw transit UUIDs as ReactFlow ids hide edges.
                       (http-backend/normalize-map-ids (:body resp))])
         (rf/dispatch [:time-travel/fetch-failure
                       (error-message resp "Could not load that session")]))))))

(rf/reg-fx
 :session/create-fx
 (fn [{:keys [map-id]}]
   (go
     (let [resp (<! (api/create-session map-id))]
       (if (= 201 (:status resp))
         (rf/dispatch [:session/start-success map-id (:body resp)])
         (rf/dispatch [:session/start-failure
                       (error-message resp "Could not start a session")]))))))

(rf/reg-fx
 :session/update-trigger-fx
 (fn [{:keys [map-id session-id trigger]}]
   (go
     (let [resp (<! (api/update-session-trigger map-id session-id trigger))]
       ;; The text was set optimistically; success just confirms it so
       ;; the quiet "Saved" indicator can show.
       (if (= 200 (:status resp))
         (rf/dispatch [:session/trigger-saved])
         (rf/dispatch [:session/trigger-save-failure map-id
                       (error-message resp "Could not save the trigger")]))))))

(rf/reg-fx
 :session/delete-fx
 (fn [{:keys [map-id session-id]}]
   (go
     (let [resp (<! (api/delete-session map-id session-id))]
       (if (= 204 (:status resp))
         (rf/dispatch [:session/delete-success session-id])
         (rf/dispatch [:session/delete-failure
                       (error-message resp "Could not delete the session")]))))))

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
