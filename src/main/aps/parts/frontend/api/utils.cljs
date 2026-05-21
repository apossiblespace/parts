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

;; Auth is an httpOnly session cookie (ADR-0007). The browser carries it on
;; every same-origin request — there is no token to store or read here.

(defn get-csrf-token
  "Get the CSRF token from the `<meta name=\"csrf-token\">` tag rendered into
   the app shell. Sent as the `X-CSRF-Token` header on mutating requests."
  []
  (when-let [meta-tag (.querySelector js/document "meta[name='csrf-token']")]
    (.getAttribute meta-tag "content")))
