(ns parts.frontend.api.core
  "High level API functions that should be used to interact with the backend."
  (:require
   [cljs.core.async :refer [<! go]]
   [parts.frontend.api.http :as http]
   [parts.frontend.api.utils :as utils]))

;; Authentication-related functions
(defn login
  "CREDENTIALS should be a map containing the keys :email and :password."
  [credentials]
  (go
    (let [response (<! (http/POST "/auth/login" credentials {:skip-auth true}))]
      (when (= 200 (:status response))
        (utils/save-tokens (:body response)))
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

;; NOTE: This function is called in the queue processing go-loop in
;; parts.frontend.api.queue/start. The idea is that a processing queue is
;; started _per system_, so it makes sense to enclose the system-id in the
;; `start` function.
(defn send-batched-updates
  [system-id batch]
  (go
    (js/console.log "[queue][send-batched-updates]" batch)
    (<! (http/POST (str "/systems/" system-id "/changes") batch))))
