(ns parts.frontend.storage.localstorage-backend
  "LocalStorage storage backend implementation with single-tab editing enforcement."
  (:require
   [cljs.core.async :refer [go]]
   [parts.frontend.storage.protocol :refer [StorageBackend]]
   [parts.frontend.observe :as o]))

(defn- storage-key
  "Returns the localStorage key for a system"
  [system-id]
  (str "parts-system-" system-id))

(defn- get-system-from-storage
  "Gets system data from localStorage"
  [system-id]
  (try
    (when-let [data (js/localStorage.getItem (storage-key system-id))]
      (js->clj (js/JSON.parse data) :keywordize-keys true))
    (catch js/Error e
      (o/error "localstorage-backend.get-system" "failed to parse system data" system-id e)
      nil)))

(defn- save-system-to-storage
  "Saves system data to localStorage without blocking UI"
  [system-id system-data]
  (o/debug "localstorage-backend.save-system" "saving system" system-id (storage-key system-id))
  (try
    (let [json-str (js/JSON.stringify (clj->js system-data))]
      ;; Use setTimeout to avoid blocking the UI
      (js/setTimeout
       #(js/localStorage.setItem (storage-key system-id) json-str)
       0))
    (catch js/Error e
      (o/error "localstorage-backend.save-system" "failed to save system data" system-id e))))

(defn- get-all-system-keys
  "Gets all system keys from localStorage"
  []
  (try
    (let [keys (for [i (range (.-length js/localStorage))
                     :let [key (js/localStorage.key i)]
                     :when (and key (.startsWith key "parts-system-"))]
                 key)]
      keys)
    (catch js/Error e
      (o/error "localstorage-backend.get-all-keys" "failed to get system keys" e)
      [])))

(defn- extract-system-id
  "Extracts system ID from storage key"
  [storage-key]
  (when (.startsWith storage-key "parts-system-")
    (.substring storage-key 13))) ;; length of "parts-system-"

(defn- apply-change-to-system
  "Applies a single change event to system data"
  [system-data {:keys [entity id type data] :as event}]
  (try
    (case [entity type]
      [:part "create"]
      (let [new-part (assoc data :id id :system_id (:id system-data))]
        (update system-data :parts conj new-part))

      [:part "update"]
      (update system-data :parts
              (fn [parts]
                (mapv (fn [part]
                        (if (= (:id part) id)
                          (merge part data)
                          part))
                      parts)))

      [:part "position"]
      (update system-data :parts
              (fn [parts]
                (mapv (fn [part]
                        (if (= (:id part) id)
                          (merge part {:position_x (int (:x data))
                                       :position_y (int (:y data))})
                          part))
                      parts)))

      [:part "remove"]
      (update system-data :parts
              (fn [parts]
                (filterv #(not= (:id %) id) parts)))

      [:relationship "create"]
      (let [new-rel (assoc data :id id :system_id (:id system-data))]
        (update system-data :relationships conj new-rel))

      [:relationship "update"]
      (update system-data :relationships
              (fn [rels]
                (mapv (fn [rel]
                        (if (= (:id rel) id)
                          (merge rel data)
                          rel))
                      rels)))

      [:relationship "remove"]
      (update system-data :relationships
              (fn [rels]
                (filterv #(not= (:id %) id) rels)))

      ;; Default case - unknown change type
      (do
        (o/warn "localstorage-backend.apply-change" "unknown change type" entity type)
        system-data))
    (catch js/Error e
      (o/error "localstorage-backend.apply-change" "failed to apply change" event e)
      system-data)))

(defrecord LocalStorageBackend []
  StorageBackend

  (list-systems [_this]
    "Lists systems from localStorage"
    (go
      (o/debug "localstorage-backend.list-systems" "listing systems")
      (try
        (let [system-keys (get-all-system-keys)
              systems (keep (fn [key]
                              (when-let [system-id (extract-system-id key)]
                                (when-let [system (get-system-from-storage system-id)]
                                  {:id (:id system)
                                   :title (:title system "Untitled System")
                                   :last_modified (:last_modified system)})))
                            system-keys)]
          systems)
        (catch js/Error e
          (o/error "localstorage-backend.list-systems" "failed to list systems" e)
          []))))

  (load-system [_this system-id]
    "Loads system from localStorage"
    (go
      (o/debug "localstorage-backend.load-system" "loading system" system-id)
      (get-system-from-storage system-id)))

  (create-system [_this system-data]
    "Creates a new system in localStorage"
    (go
      (o/debug "localstorage-backend.create-system" "creating system" system-data)
      (let [system-id (str (random-uuid))
            new-system (merge {:id system-id
                               :parts []
                               :relationships []
                               :created_at (js/Date.now)
                               :last_modified (js/Date.now)}
                              system-data)
            system-id (:id new-system)]
        (save-system-to-storage system-id new-system)
        new-system)))

  (update-system [_this system-id system-data]
    "Updates system metadata in localStorage"
    (go
      (o/debug "localstorage-backend.update-system" "updating system" system-id)
      (when-let [current-system (get-system-from-storage system-id)]
        (let [updated-system (merge current-system
                                    (select-keys system-data [:title :viewport_settings])
                                    {:last_modified (js/Date.now)})]
          (save-system-to-storage system-id updated-system)
          updated-system))))

  (process-batched-changes [_this system-id batch]
    "Processes batched changes in localStorage"
    (go
      (o/debug "localstorage-backend.process-batch" "processing batch for system" system-id "changes:" batch)
      (when-let [current-system (get-system-from-storage system-id)]
        ;; Apply all changes sequentially
        (let [updated-system (reduce apply-change-to-system current-system batch)
              updated-with-timestamp (assoc updated-system :last_modified (js/Date.now))]
          (save-system-to-storage system-id updated-with-timestamp)
          {:success true :result updated-with-timestamp})))))

(defn create-localstorage-backend
  "Creates a new localStorage storage backend instance"
  []
  (->LocalStorageBackend))
