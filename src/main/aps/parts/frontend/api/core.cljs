(ns aps.parts.frontend.api.core
  "High level API functions that should be used to interact with the backend."
  (:require
   [aps.parts.frontend.api.http :as http]
   [aps.parts.frontend.api.utils :as utils]
   [cljs.core.async :refer [<! go]]))

;; Authentication-related functions
(defn login
  "CREDENTIALS should be a map containing the keys :email and :password.
   On success the server sets the auth-session cookie — nothing to store."
  [credentials]
  (http/POST "/auth/login" credentials))

(defn register
  "Register a new user account.
   PARAMS should be a map containing :email, :display_name, :password,
   :password_confirmation, and the onboarding acceptance booleans
   :accepted-medical? and :accepted-legal? (the server rejects
   registration without both; ADR-0009).
   On success the server sets the auth-session cookie; clears playground data."
  [params]
  (go
    (let [response (<! (http/POST "/account/register" params))]
      (when (= 201 (:status response))
        (utils/clear-playground-data))
      response)))

(defn logout
  "Log out — POST /auth/logout clears the server-side auth session and its
   cookie. Returns the request channel so callers can await it."
  []
  (http/POST "/auth/logout" {}))

;; Account-related functions
(defn get-current-user
  "Retrieve the information about the currently signed in user:

  - id
  - email
  - display name
  - role"
  []
  (http/GET "/account" {}))
