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
   On success, saves tokens for auto-login and returns the response with user + system data."
  [params]
  (go
    (let [response (<! (http/POST "/account/register" params {:skip-auth true}))]
      (when (= 201 (:status response))
        (utils/save-tokens (select-keys (:body response) [:access_token :refresh_token :token_type])))
      response)))

(defn logout []
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

(defn get-systems
  "Retrieve a list of systems for the currently signed in user:"
  []
  (http/GET "/systems"))

(defn get-system
  "Retrieve a single system identified by `id`"
  [id]
  (http/GET (str "/systems/" id)))

(defn create-system
  "Create a system with the given `params`"
  [params]
  (http/POST "/systems" params))

;; NOTE: This function is called in the queue processing go-loop in
;; aps.parts.frontend.api.queue/start. The idea is that a processing queue is
;; started _per system_, so it makes sense to enclose the system-id in the
;; `start` function.
(defn send-batched-updates
  [system-id batch]
  (go
    (o/debug "api.send-batched-updates" "sending batch" batch)
    (<! (http/POST (str "/systems/" system-id "/changes") batch))))
