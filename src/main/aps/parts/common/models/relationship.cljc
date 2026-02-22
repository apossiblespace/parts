(ns aps.parts.common.models.relationship
  (:require
   [aps.parts.common.constants :refer [relationship-types]]
   [aps.parts.common.utils :refer [validate-spec]]
   [clojure.spec.alpha :as s]))

(s/def ::id (s/or :string string? :uuid uuid?))
(s/def ::system_id (s/or :string string? :uuid uuid?))
(s/def ::type #(contains? relationship-types %))
(s/def ::source_id (s/or :string string? :uuid uuid?))
(s/def ::target_id (s/or :string string? :uuid uuid?))
(s/def ::notes (s/nilable string?))

(s/def ::relationship
  (s/keys :req-un [::system_id
                   ::type
                   ::source_id
                   ::target_id]
          :opt-un [::id
                   ::notes]))

(def spec
  "Relationship model spec for reuse outside of the namespace"
  ::relationship)

(defn make-relationship
  "Create a new Relationship with the given attributes. 
   In ClojureScript (frontend), generates a string UUID for :id. 
   In Clojure (backend), :id is set by the database layer."
  [attrs]
  (println "[make-relationship]" attrs)
  (let [base         {:type  "unknown"
                      :notes nil}
        relationship #?(:cljs (merge {:id (str (random-uuid))} base attrs)
                        :clj (merge base attrs))]
    (validate-spec ::relationship relationship)
    relationship))

(s/def ::relationship-update
  (s/keys :opt-un [::id
                   ::system_id
                   ::type
                   ::source_id
                   ::target_id
                   ::notes]))

(defn validate-update
  "Validate a partial relationship update map. Any fields present must conform to their specs."
  [attrs]
  (validate-spec ::relationship-update attrs))
