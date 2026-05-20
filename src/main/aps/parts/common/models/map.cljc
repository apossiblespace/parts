(ns aps.parts.common.models.map
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.common.utils :refer [validate-spec]]
   [clojure.spec.alpha :as s]))

(s/def ::id (s/or :string string? :uuid uuid?))
(s/def ::title string?)
(s/def ::owner_id (s/or :string string? :uuid uuid?))

;; Note: `viewport_settings` used to live here. It's now browser-local UI
;; state, not server-stored. If cross-device persistence becomes a product
;; requirement, add a per-user-per-map table — non-temporal.

(s/def ::map (s/keys :req-un [::title
                              ::owner_id]
                     :opt-un [::id]))

(def spec
  "Map model spec for reuse outside of the namespace"
  ::map)

(defn make-map
  "Create a new Map with the given attributes.
   In ClojureScript (frontend), generates a string UUID for :id.
   In Clojure (backend), :id is set by the database layer."
  [attrs]
  (let [base    {:title "Untitled Map"}
        the-map #?(:cljs (merge {:id (str (random-uuid))} base attrs)
                   :clj (merge base attrs))]
    (o/debug "[make-map]" the-map)
    (validate-spec ::map the-map)
    the-map))

(s/def ::map-update
  (s/keys :opt-un [::id
                   ::title
                   ::owner_id]))

(defn validate-update
  "Validate a partial map update map. Any fields present must conform to their specs."
  [attrs]
  (validate-spec ::map-update attrs))
