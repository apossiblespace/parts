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

;; Session-related functions (ADR-0014). Out-of-band REST, not
;; change-events — each is a single-row write to the non-temporal
;; sessions table.
(defn list-sessions
  "A Map's Sessions, ordered by anchor."
  [map-id]
  (http/GET (str "/maps/" map-id "/sessions") {}))

(defn load-map-at
  "The Map as it stood at the end of a Session's range — the Time-travel
   view. Same response shape as the live map fetch."
  [map-id session-id]
  (http/GET (str "/maps/" map-id) {:at session-id}))

(defn create-session
  "Open a new Session. The anchor and ordinal are server-side; the
   trigger starts empty and is set afterwards via `update-session-trigger`."
  [map-id]
  (http/POST (str "/maps/" map-id "/sessions") {}))

(defn update-session-trigger
  "Set the active Session's trigger text. The server refuses for a past
   Session (the past is read-only)."
  [map-id session-id trigger]
  (http/PUT (str "/maps/" map-id "/sessions/" session-id) {:trigger trigger}))

(defn delete-session
  "Delete a Session — the server only allows the latest-and-empty one."
  [map-id session-id]
  (http/DELETE (str "/maps/" map-id "/sessions/" session-id)))

(defn set-session-activation
  "Link the Part this Session activated (one per Session at launch)."
  [map-id session-id part-id]
  (http/PUT (str "/maps/" map-id "/sessions/" session-id "/activation")
    {:part_id part-id}))

(defn clear-session-activation
  "Remove the Session's activated-Part link."
  [map-id session-id]
  (http/DELETE (str "/maps/" map-id "/sessions/" session-id "/activation")))

;; Account-related functions
(defn get-current-user
  "Retrieve the information about the currently signed in user:

  - id
  - email
  - display name
  - role"
  []
  (http/GET "/account" {}))
