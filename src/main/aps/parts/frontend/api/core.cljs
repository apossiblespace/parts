(ns aps.parts.frontend.api.core
  "High level API functions that should be used to interact with the backend."
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.api.http :as http]
   [aps.parts.frontend.api.utils :as utils]
   [cljs.core.async :refer [<! go]]))

;; Authentication-related functions
(defn login
  "CREDENTIALS should be a map containing the keys :email and :password."
  [credentials]
  (go
    (let [response (<! (http/POST "/auth/login" credentials {:skip-auth true}))]
      (when (= 200 (:status response))
        (utils/save-tokens (:body response)))
      response)))

(defn register
  "Register a new user account.
   PARAMS should be a map containing :email, :username, :display_name, :password, :password_confirmation.
   On success, saves tokens for auto-login and clears any playground data."
  [params]
  (go
    (let [response (<! (http/POST "/account/register" params {:skip-auth true}))]
      (when (= 201 (:status response))
        (utils/save-tokens (select-keys (:body response) [:access_token :refresh_token :token_type]))
        (utils/clear-playground-data))
      response)))

(defn logout
  "Log out the current user by clearing tokens."
  []
  (utils/clear-tokens))

;; Account-related functions
(defn get-current-user
  "Retrieve the information about the currently signed in user:

  - id
  - email
  - username
  - display name
  - role"
  []
  (http/GET "/account" {}))

(defn get-maps
  "Retrieve a list of maps for the currently signed in user:"
  []
  (http/GET "/maps"))

(defn get-map
  "Retrieve a single map identified by `id`"
  [id]
  (http/GET (str "/maps/" id)))

(defn create-map
  "Create a map with the given `params`"
  [params]
  (http/POST "/maps" params))

;; NOTE: This function is called in the queue processing go-loop in
;; aps.parts.frontend.api.queue/start. The idea is that a processing queue is
;; started _per map_, so it makes sense to enclose the map-id in the
;; `start` function.
(defn send-batched-updates
  [map-id batch]
  (go
    (o/debug "api.send-batched-updates" "sending batch" batch)
    (<! (http/POST (str "/maps/" map-id "/changes") batch))))
