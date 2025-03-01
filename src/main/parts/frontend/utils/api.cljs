(ns parts.frontend.utils.api
  (:require
   [cljs.core.async :refer [chan put! <!]]
   [cljs.core.async.interop :refer-macros [<p!]])
  (:require-macros
   [cljs.core.async.macros :refer [go]]))

(def ^:private token-storage-key "parts-auth-tokens")
(def ^:private user-email-key "parts-user-email")
(def csrf-token-name "__anti-forgery-token")

(defn save-tokens
  "Save authentication tokens to local storage"
  [tokens]
  (.setItem js/localStorage token-storage-key (js/JSON.stringify (clj->js tokens))))

(defn get-tokens
  "Get authentication tokens from local storage"
  []
  (when-let [tokens-str (.getItem js/localStorage token-storage-key)]
    (js->clj (.parse js/JSON tokens-str) :keywordize-keys true)))

(defn save-user-email
  "Save user email to local storage"
  [email]
  (.setItem js/localStorage user-email-key email))

(defn get-user-email
  "Get user email from local storage"
  []
  (.getItem js/localStorage user-email-key))

(defn clear-tokens
  "Clear authentication tokens from local storage"
  []
  (.removeItem js/localStorage token-storage-key)
  (.removeItem js/localStorage user-email-key))

(defn get-auth-header
  "Get the Authorization header for authenticated requests"
  []
  (when-let [tokens (get-tokens)]
    (str (:token_type tokens) " " (:access_token tokens))))

(defn get-csrf-token
  "Get the CSRF token from the meta tag"
  []
  (when-let [meta-tag (.querySelector js/document "meta[name='csrf-token']")]
    (.getAttribute meta-tag "content")))

(defn api-request
  "Make a request to the API, optionally with authentication"
  [{:keys [url method data authenticated? as-form?]
    :or {method "GET"
         authenticated? false
         as-form? false}}]
  (let [result-chan (chan)
        csrf-token (get-csrf-token)
        headers (js/Object.)
        _ (when (not as-form?)
            (aset headers "Content-Type" "application/json"))
        _ (when authenticated?
            (when-let [auth-header (get-auth-header)]
              (aset headers "Authorization" auth-header)))
        _ (js/console.log "API request:", url, method, as-form?, "data:", data)]
    (go
      (try
        (let [request-data (if data 
                             data 
                             {})
              fetch-opts (if as-form?
                           ;; For form data submission
                           (let [form-data (js/URLSearchParams.)]
                             ;; Add all data as form fields
                             (doseq [[k v] request-data]
                               (when v
                                 (js/console.log "Adding to form data:" (name k) v)
                                 (.append form-data (name k) v)))
                             
                             ;; Set proper content type for form submissions
                             (aset headers "Content-Type" "application/x-www-form-urlencoded")
                             
                             ;; Create fetch options
                             (clj->js {:method method
                                      :headers headers
                                      :body form-data}))
                           ;; For JSON, use the standard approach
                           (clj->js {:method method
                                    :headers headers
                                    :body (js/JSON.stringify (clj->js request-data))}))
              response (<p! (js/fetch url fetch-opts))
              status (.-status response)
              content-type (.get (.-headers response) "content-type")
              _ (js/console.log "Response content type:", content-type)
              is-json (when content-type (re-find #"application/json" content-type))
              body (<p! (if is-json
                          (.json response)
                          (.text response)))
              parsed-body (if is-json (js->clj body :keywordize-keys true) body)]
          (js/console.log "API Response:", status, parsed-body)
          (if (< status 400)
            (do
              (js/console.log "API Success:", parsed-body)
              (put! result-chan {:success true :data parsed-body}))
            (do
              (js/console.log "API Error:", status, parsed-body)
              (put! result-chan {:success false :error parsed-body}))))
        (catch js/Error e
          (put! result-chan {:success false :error (.-message e)}))))
    result-chan))

(defn login
  "Attempt to log in with email and password"
  [email password csrf-token]
  (js/console.log "Attempting login with:" email "token:" csrf-token)
  (api-request
   {:url "/api/auth/login"
    :method "POST"
    :as-form? true
    :data {"email" email 
           "password" password
           "__anti-forgery-token" csrf-token}}))

(defn logout
  "Log out the current user"
  []
  (let [tokens (get-tokens)
        csrf-token (get-csrf-token)
        result-chan (chan)]
    (if tokens
      (go
        (let [response (<! (api-request
                            {:url "/api/auth/logout"
                             :method "POST"
                             :authenticated? true
                             :as-form? true
                             :data {"refresh_token" (:refresh_token tokens)
                                    "__anti-forgery-token" csrf-token}}))]
          (clear-tokens)
          (put! result-chan {:success true})))
      (put! result-chan {:success true}))
    result-chan))

(defn get-user-info
  "Get the current user's information"
  []
  (api-request
   {:url "/api/account"
    :method "GET"
    :authenticated? true}))