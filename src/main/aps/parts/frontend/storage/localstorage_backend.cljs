(ns aps.parts.frontend.storage.localstorage-backend
  "LocalStorage storage backend implementation with single-tab editing enforcement."
  (:require
   [aps.parts.common.models.map :as map-model]
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.storage.protocol :refer [StorageBackend]]
   [cljs.core.async :refer [go]]))

(def ^:private storage-key-prefix "parts-map-")

(defn- storage-key
  "Returns the localStorage key for a map"
  [map-id]
  (str storage-key-prefix map-id))

(defn- get-map-from-storage
  "Gets map data from localStorage"
  [map-id]
  (try
    (when-let [data (js/localStorage.getItem (storage-key map-id))]
      (js->clj (js/JSON.parse data) :keywordize-keys true))
    (catch js/Error e
      (o/error "localstorage-backend.get-map" "failed to parse map data" map-id e)
      nil)))

(defn- save-map-to-storage
  "Saves map data to localStorage without blocking UI"
  [map-id map-data]
  (o/debug "localstorage-backend.save-map" "saving map" map-id (storage-key map-id))
  (try
    (let [json-str (js/JSON.stringify (clj->js map-data))]
      ;; Use setTimeout to avoid blocking the UI
      (js/setTimeout
       #(js/localStorage.setItem (storage-key map-id) json-str)
       0))
    (catch js/Error e
      (o/error "localstorage-backend.save-map" "failed to save map data" map-id e))))

(defn- get-all-map-keys
  "Gets all map keys from localStorage"
  []
  (try
    (let [keys (for [i     (range (.-length js/localStorage))
                     :let  [key (js/localStorage.key i)]
                     :when (and key (.startsWith key storage-key-prefix))]
                 key)]
      keys)
    (catch js/Error e
      (o/error "localstorage-backend.get-all-keys" "failed to get map keys" e)
      [])))

(defn- extract-map-id
  "Extracts map ID from storage key"
  [storage-key]
  (when (.startsWith storage-key storage-key-prefix)
    (.substring storage-key (count storage-key-prefix))))

(defn- apply-change-to-map
  "Applies a single change event to map data"
  [map-data {:keys [entity id type data] :as event}]
  (try
    ;; A canvas move is a :part :update carrying {:position_x :position_y}
    (case [entity type]
      [:part :create]
      (let [new-part (assoc data :id id :map_id (:id map-data))]
        (update map-data :parts conj new-part))

      [:part :update]
      (update map-data :parts
              (fn [parts]
                (mapv (fn [part]
                        (if (= (:id part) id)
                          (merge part data)
                          part))
                      parts)))

      [:part :remove]
      (update map-data :parts
              (fn [parts]
                (filterv #(not= (:id %) id) parts)))

      [:relationship :create]
      (let [new-rel (assoc data :id id :map_id (:id map-data))]
        (update map-data :relationships conj new-rel))

      [:relationship :update]
      (update map-data :relationships
              (fn [rels]
                (mapv (fn [rel]
                        (if (= (:id rel) id)
                          (merge rel data)
                          rel))
                      rels)))

      [:relationship :remove]
      (update map-data :relationships
              (fn [rels]
                (filterv #(not= (:id %) id) rels)))

      ;; Default case - unknown change type
      (do
        (o/warn "localstorage-backend.apply-change" "unknown change type" entity type)
        map-data))
    (catch js/Error e
      (o/error "localstorage-backend.apply-change" "failed to apply change" event e)
      map-data)))

(defrecord LocalStorageBackend []
  StorageBackend

  (list-maps [_this]
    "Lists maps from localStorage"
    (go
      (o/debug "localstorage-backend.list-maps" "listing maps")
      (try
        (let [map-keys (get-all-map-keys)
              maps     (keep (fn [key]
                               (when-let [map-id (extract-map-id key)]
                                 (when-let [the-map (get-map-from-storage map-id)]
                                   {:id            (:id the-map)
                                    :title         (:title the-map map-model/default-title)
                                    :last_modified (:last_modified the-map)})))
                             map-keys)]
          maps)
        (catch js/Error e
          (o/error "localstorage-backend.list-maps" "failed to list maps" e)
          []))))

  (load-map [_this map-id]
    "Loads map from localStorage"
    (go
      (o/debug "localstorage-backend.load-map" "loading map" map-id)
      (get-map-from-storage map-id)))

  (create-map [_this map-data]
    "Creates a new map in localStorage"
    (go
      (o/debug "localstorage-backend.create-map" "creating map" map-data)
      (let [map-id  (str (random-uuid))
            new-map (merge {:id            map-id
                            :parts         []
                            :relationships []
                            :created_at    (js/Date.now)
                            :last_modified (js/Date.now)}
                           map-data)
            map-id  (:id new-map)]
        (save-map-to-storage map-id new-map)
        new-map)))

  (update-map [_this map-id map-data]
    "Updates map metadata in localStorage"
    (go
      (o/debug "localstorage-backend.update-map" "updating map" map-id)
      (when-let [current-map (get-map-from-storage map-id)]
        (let [updated-map (merge current-map
                                 (select-keys map-data [:title :viewport_settings])
                                 {:last_modified (js/Date.now)})]
          (save-map-to-storage map-id updated-map)
          updated-map))))

  (process-batched-changes [_this map-id batch]
    "Processes batched changes in localStorage"
    (go
      (o/debug "localstorage-backend.process-batch" "processing batch for map" map-id "changes:" batch)
      (when-let [current-map (get-map-from-storage map-id)]
        ;; Apply all changes sequentially
        (let [updated-map            (reduce apply-change-to-map current-map batch)
              updated-with-timestamp (assoc updated-map :last_modified (js/Date.now))]
          (save-map-to-storage map-id updated-with-timestamp)
          {:success true :result updated-with-timestamp})))))

(defn create-localstorage-backend
  "Creates a new localStorage storage backend instance"
  []
  (->LocalStorageBackend))
