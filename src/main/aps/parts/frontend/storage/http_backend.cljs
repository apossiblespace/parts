(ns aps.parts.frontend.storage.http-backend
  "HTTP API storage backend implementation."
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.api.http :as http]
   [aps.parts.frontend.storage.protocol :refer [StorageBackend]]
   [cljs.core.async :refer [<! go]]))

(defn- normalize-map-ids
  "Converts all UUID objects in a map's data to strings.
   Transit decodes Java UUIDs as CLJS UUID objects, but the rest of the app
   (ReactFlow adapters, localStorage backend) expects plain strings."
  [the-map]
  (-> the-map
      (update :id str)
      (update :parts
              (fn [parts]
                (mapv #(-> % (update :id str) (update :map_id str))
                      parts)))
      (update :relationships
              (fn [rels]
                (mapv #(-> %
                           (update :id str)
                           (update :map_id str)
                           (update :source_id str)
                           (update :target_id str))
                      rels)))))

(defrecord HttpBackend []
  StorageBackend

  (list-maps [_this]
    "Lists maps by making GET request to /maps endpoint."
    (go
      (o/debug "http-backend.list-maps" "listing maps")
      (let [response (<! (http/GET "/maps"))]
        (case (:status response)
          200 (:body response)
          401 (do
                (o/warn "http-backend.list-maps" "unauthorized")
                {:error :unauthorized})
          (do
            (o/error "http-backend.list-maps" "failed to list maps" response)
            [])))))

  (load-map [_this map-id]
    "Loads map by making GET request to /maps/:id endpoint.
     Returns the map on success, or {:error :unauthorized} on 401,
     {:error :forbidden} on 403, {:error :not-found} on 404,
     or {:error :unknown} on other failures."
    (go
      (o/debug "http-backend.load-map" "loading map" map-id)
      (let [response (<! (http/GET (str "/maps/" map-id)))]
        (case (:status response)
          200 (normalize-map-ids (:body response))
          401 (do
                (o/warn "http-backend.load-map" "unauthorized" map-id)
                {:error :unauthorized})
          403 (do
                (o/warn "http-backend.load-map" "forbidden" map-id)
                {:error :forbidden})
          404 (do
                (o/warn "http-backend.load-map" "not found" map-id)
                {:error :not-found})
          (do
            (o/error "http-backend.load-map" "failed to load map" map-id response)
            {:error :unknown})))))

  (create-map [_this map-data]
    "Creates map by making POST request to /maps endpoint."
    (go
      (o/debug "http-backend.create-map" "creating map" map-data)
      (let [response (<! (http/POST "/maps" map-data))]
        (case (:status response)
          201 (:body response)
          401 (do
                (o/warn "http-backend.create-map" "unauthorized")
                {:error :unauthorized})
          (do
            (o/error "http-backend.create-map" "failed to create map" response)
            nil)))))

  (update-map [_this map-id map-data]
    "Updates map metadata by making PUT request to /maps/:id endpoint."
    (go
      (o/debug "http-backend.update-map" "updating map" map-id)
      (let [response (<! (http/PUT (str "/maps/" map-id) map-data))]
        (if (= 200 (:status response))
          (:body response)
          (do
            (o/error "http-backend.update-map" "failed to update map" map-id response)
            nil)))))

  (process-batched-changes [_this map-id batch]
    "Processes batched changes by making POST request to /maps/:id/changes endpoint."
    (go
      (o/debug "http-backend.process-batch" "processing batch for map" map-id "changes:" batch)
      (let [response (<! (http/POST (str "/maps/" map-id "/changes") batch))]
        (if (= 200 (:status response))
          (:body response)
          (do
            (o/error "http-backend.process-batch" "failed to process batch" map-id response)
            nil))))))

(defn create-http-backend
  "Creates a new HTTP API storage backend instance."
  []
  (->HttpBackend))
