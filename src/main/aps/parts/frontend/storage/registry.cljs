(ns aps.parts.frontend.storage.registry
  "Storage backend registry for managing the current storage backend instance."
  (:require
   [aps.parts.frontend.storage.http-backend :refer [create-http-backend]]
   [aps.parts.frontend.storage.localstorage-backend :refer [create-localstorage-backend]]))

(defonce ^:private current-backend (atom nil))

(defn set-backend!
  "Sets the current storage backend instance."
  [backend]
  (reset! current-backend backend))

(defn get-backend
  "Returns the current storage backend instance."
  []
  @current-backend)

(defn init-http-backend!
  "Initializes and sets the HTTP storage backend as current."
  []
  (set-backend! (create-http-backend)))

(defn init-localstorage-backend!
  "Initializes and sets the localStorage storage backend as current."
  []
  (set-backend! (create-localstorage-backend)))