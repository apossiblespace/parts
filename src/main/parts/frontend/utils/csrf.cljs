(ns parts.frontend.utils.csrf)

(defn get-token
  "Get the CSRF token from the meta tag"
  []
  (when-let [meta-tag (.querySelector js/document "meta[name='csrf-token']")]
    (.getAttribute meta-tag "content")))
