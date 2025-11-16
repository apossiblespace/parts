(ns aps.parts.frontend.storage.http-backend
  "HTTP API storage backend implementation."
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.api.http :as http]
   [aps.parts.frontend.storage.protocol :refer [StorageBackend]]
   [cljs.core.async :refer [<! go]]))

(defrecord HttpBackend []
  StorageBackend

  (list-systems [_this]
    "Lists systems by making GET request to /systems endpoint."
    (go
      (o/debug "http-backend.list-systems" "listing systems")
      (let [response (<! (http/GET "/systems"))]
        (if (= 200 (:status response))
          (:body response)
          (do
            (o/error "http-backend.list-systems" "failed to list systems" response)
            [])))))

  (load-system [_this system-id]
    "Loads system by making GET request to /systems/:id endpoint."
    (go
      (o/debug "http-backend.load-system" "loading system" system-id)
      (let [response (<! (http/GET (str "/systems/" system-id)))]
        (if (= 200 (:status response))
          (:body response)
          (do
            (o/error "http-backend.load-system" "failed to load system" system-id response)
            nil)))))

  (create-system [_this system-data]
    "Creates system by making POST request to /systems endpoint."
    (go
      (o/debug "http-backend.create-system" "creating system" system-data)
      (let [response (<! (http/POST "/systems" system-data))]
        (if (= 201 (:status response))
          (:body response)
          (do
            (o/error "http-backend.create-system" "failed to create system" response)
            nil)))))

  (update-system [_this system-id system-data]
    "Updates system metadata by making PUT request to /systems/:id endpoint."
    (go
      (o/debug "http-backend.update-system" "updating system" system-id)
      (let [response (<! (http/PUT (str "/systems/" system-id) system-data))]
        (if (= 200 (:status response))
          (:body response)
          (do
            (o/error "http-backend.update-system" "failed to update system" system-id response)
            nil)))))

  (process-batched-changes [_this system-id batch]
    "Processes batched changes by making POST request to /systems/:id/changes endpoint."
    (go
      (o/debug "http-backend.process-batch" "processing batch for system" system-id "changes:" batch)
      (let [response (<! (http/POST (str "/systems/" system-id "/changes") batch))]
        (if (= 200 (:status response))
          (:body response)
          (do
            (o/error "http-backend.process-batch" "failed to process batch" system-id response)
            nil))))))

(defn create-http-backend
  "Creates a new HTTP API storage backend instance."
  []
  (->HttpBackend))