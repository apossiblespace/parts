(ns aps.parts.frontend.api.utils)

(def ^:private current-map-storage-key "parts-current-map-id")

(defn save-current-map-id [id]
  (.setItem js/localStorage current-map-storage-key id))

(defn get-current-map-id []
  (.getItem js/localStorage current-map-storage-key))

(defn clear-current-map-id []
  (.removeItem js/localStorage current-map-storage-key))

(defn clear-playground-data
  "Clears all playground-related localStorage (maps + current ID)"
  []
  (clear-current-map-id)
  ;; Remove all parts-map-* keys
  (let [keys-to-remove (for [i     (range (.-length js/localStorage))
                             :let  [key (.key js/localStorage i)]
                             :when (and key (.startsWith key "parts-map-"))]
                         key)]
    (doseq [key keys-to-remove]
      (.removeItem js/localStorage key))))

(def ^:private token-storage-key "parts-auth-tokens")

(defn save-tokens
  "Save authentication tokens to local storage"
  [tokens]
  (.setItem js/localStorage token-storage-key (js/JSON.stringify (clj->js tokens))))

(defn get-tokens
  "Get authentication tokens from local storage"
  []
  (when-let [tokens-str (.getItem js/localStorage token-storage-key)]
    (js->clj (.parse js/JSON tokens-str) :keywordize-keys true)))

(defn clear-tokens
  "Clear authentication tokens from local storage"
  []
  (.removeItem js/localStorage token-storage-key))

(defn auth-header
  "Get the Authorization header for authenticated requests from tokens"
  [tokens]
  (when (and (:token_type tokens) (:access_token tokens))
    (str (:token_type tokens) " " (:access_token tokens))))

(defn get-csrf-token
  "Get the CSRF token from the meta tag"
  []
  (when-let [meta-tag (.querySelector js/document "meta[name='csrf-token']")]
    (.getAttribute meta-tag "content")))
