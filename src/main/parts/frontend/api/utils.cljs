(ns parts.frontend.api.utils)

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
