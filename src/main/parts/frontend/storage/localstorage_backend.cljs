(ns parts.frontend.storage.localstorage-backend
  "LocalStorage storage backend implementation with single-tab editing enforcement."
  (:require
   [cljs.core.async :refer [chan go put! timeout <! alts!]]
   [parts.frontend.storage.protocol :refer [StorageBackend]]
   [parts.common.models.part :as part-model]
   [parts.common.models.relationship :as relationship-model]))

(defn- storage-key
  "Returns the localStorage key for a system"
  [system-id]
  (str "parts-system-" system-id))

(defn- lock-key
  "Returns the localStorage key for tab locking"
  [system-id]
  (str "parts-active-tab-" system-id))

(defn- get-system-from-storage
  "Gets system data from localStorage"
  [system-id]
  (try
    (when-let [data (js/localStorage.getItem (storage-key system-id))]
      (js->clj (js/JSON.parse data) :keywordize-keys true))
    (catch js/Error e
      (js/console.error "[storage][localstorage-backend] failed to parse system data:" system-id e)
      nil)))

(defn- save-system-to-storage
  "Saves system data to localStorage without blocking UI"
  [system-id system-data]
  (try
    (let [json-str (js/JSON.stringify (clj->js system-data))]
      ;; Use setTimeout to avoid blocking the UI
      (js/setTimeout
       #(js/localStorage.setItem (storage-key system-id) json-str)
       0))
    (catch js/Error e
      (js/console.error "[storage][localstorage-backend] failed to save system data:" system-id e))))

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
      (js/console.error "[storage][localstorage-backend] failed to get system keys:" e)
      [])))

(defn- extract-system-id
  "Extracts system ID from storage key"
  [storage-key]
  (when (.startsWith storage-key "parts-system-")
    (.substring storage-key 13))) ;; length of "parts-system-"

(defn- acquire-tab-lock
  "Attempts to acquire tab lock for system editing"
  [system-id]
  (let [lock-key-str (lock-key system-id)
        timestamp (js/Date.now)]
    (try
      (js/localStorage.setItem lock-key-str (str timestamp))
      true
      (catch js/Error e
        (js/console.error "[storage][localstorage-backend] failed to acquire lock:" system-id e)
        false))))

(defn- release-tab-lock
  "Releases tab lock for system editing"
  [system-id]
  (try
    (js/localStorage.removeItem (lock-key system-id))
    (catch js/Error e
      (js/console.error "[storage][localstorage-backend] failed to release lock:" system-id e))))

(defn- check-tab-lock
  "Checks if another tab has the lock (returns true if another tab is active)"
  [system-id]
  (try
    (when-let [lock-timestamp (js/localStorage.getItem (lock-key system-id))]
      (let [timestamp (js/parseInt lock-timestamp)
            now (js/Date.now)
            stale-threshold 30000] ;; 30 seconds
        ;; If lock is fresh (less than 30 seconds old), another tab is active
        (< (- now timestamp) stale-threshold)))
    (catch js/Error e
      (js/console.error "[storage][localstorage-backend] failed to check lock:" system-id e)
      false)))

(defn- update-tab-heartbeat
  "Updates tab heartbeat to maintain lock"
  [system-id]
  (acquire-tab-lock system-id))

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
        (js/console.warn "[storage][localstorage-backend] unknown change type:" entity type)
        system-data))
    (catch js/Error e
      (js/console.error "[storage][localstorage-backend] failed to apply change:" event e)
      system-data)))

(defrecord LocalStorageBackend [tab-locks]
  StorageBackend

  (list-systems [_this]
    "Lists systems from localStorage"
    (go
      (js/console.log "[storage][localstorage-backend] listing systems")
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
          (js/console.error "[storage][localstorage-backend] failed to list systems:" e)
          []))))

  (load-system [_this system-id]
    "Loads system from localStorage with tab lock check"
    (go
      (js/console.log "[storage][localstorage-backend] loading system:" system-id)
      (if (check-tab-lock system-id)
        (do
          (js/console.warn "[storage][localstorage-backend] system is being edited in another tab:" system-id)
          ;; Return system data but mark as read-only somehow
          ;; For now, return nil to indicate unavailable
          nil)
        (when-let [system (get-system-from-storage system-id)]
          ;; Acquire lock for this tab
          (acquire-tab-lock system-id)
          ;; Store lock in tab-locks atom for cleanup
          (swap! tab-locks conj system-id)
          system))))

  (create-system [_this system-data]
    "Creates a new system in localStorage"
    (go
      (js/console.log "[storage][localstorage-backend] creating system:" system-data)
      (let [system-id (str (random-uuid))
            new-system (merge {:id system-id
                               :parts []
                               :relationships []
                               :created_at (js/Date.now)
                               :last_modified (js/Date.now)}
                              system-data)]
        (save-system-to-storage system-id new-system)
        ;; Acquire lock for this tab
        (acquire-tab-lock system-id)
        ;; Store lock in tab-locks atom for cleanup
        (swap! tab-locks conj system-id)
        new-system)))

  (update-system [_this system-id system-data]
    "Updates system metadata in localStorage"
    (go
      (js/console.log "[storage][localstorage-backend] updating system:" system-id)
      (if (check-tab-lock system-id)
        (do
          (js/console.warn "[storage][localstorage-backend] cannot update system, another tab is active:" system-id)
          nil)
        (when-let [current-system (get-system-from-storage system-id)]
          (let [updated-system (merge current-system
                                      (select-keys system-data [:title :viewport_settings])
                                      {:last_modified (js/Date.now)})]
            (save-system-to-storage system-id updated-system)
            updated-system)))))

  (process-batched-changes [_this system-id batch]
    "Processes batched changes in localStorage"
    (go
      (js/console.log "[storage][localstorage-backend] processing batch for system:" system-id "changes:" batch)
      (if (check-tab-lock system-id)
        (do
          (js/console.warn "[storage][localstorage-backend] cannot process changes, another tab is active:" system-id)
          {:success false :error "System is being edited in another tab"})
        (when-let [current-system (get-system-from-storage system-id)]
          ;; Update heartbeat to maintain lock
          (update-tab-heartbeat system-id)
          ;; Apply all changes sequentially
          (let [updated-system (reduce apply-change-to-system current-system batch)
                updated-with-timestamp (assoc updated-system :last_modified (js/Date.now))]
            (save-system-to-storage system-id updated-with-timestamp)
            {:success true :result updated-with-timestamp}))))))

(defn create-localstorage-backend
  "Creates a new localStorage storage backend instance"
  []
  (let [tab-locks (atom #{})
        backend (->LocalStorageBackend tab-locks)]
    ;; Set up beforeunload listener to release locks
    (js/addEventListener "beforeunload"
                         (fn []
                           (doseq [system-id @tab-locks]
                             (release-tab-lock system-id))))
    ;; Set up periodic heartbeat to maintain locks
    (js/setInterval
     (fn []
       (doseq [system-id @tab-locks]
         (update-tab-heartbeat system-id)))
     10000) ;; Update every 10 seconds
    backend))