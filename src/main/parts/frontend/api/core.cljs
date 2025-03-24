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

;; Update batching related functions
;; TODO: Implement /system/changes backend endpoint
(defn send-batched-updates
  [batch]
  (go
    (println "Sending batch:" batch)
    (<! (http/POST "/system/changes" batch))))
