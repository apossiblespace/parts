(ns parts.common.models.relationship
  (:require
   [clojure.spec.alpha :as s]
   [parts.common.constants :refer [relationship-types]]
   [parts.common.utils :refer [validate-spec]]))

(s/def ::id string?)
(s/def ::system_id string?)
(s/def ::type #(contains? relationship-types %))
(s/def ::source_id string?)
(s/def ::target_id string?)
(s/def ::notes (s/nilable string?))

(s/def ::relationship
  (s/keys :req-un [::id
                   ::system_id
                   ::type
                   ::source_id
                   ::target_id]
          :opt-un [::notes]))

(def spec
  "Relationship model spec for reuse outside of the namespace"
  ::relationship)

(defn make-relationship
  "Create a new Relationship with the given attributes"
  [attrs]
  (println "[make-relationship]" attrs)
  (let [relationship (merge
                      {:id (str (random-uuid))
                       :type "unknown"
                       :notes nil}
                      attrs)]
    (validate-spec ::relationship relationship)
    relationship))
