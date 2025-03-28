(ns parts.common.models.relationship
  (:require
   [clojure.spec.alpha :as s]
   [parts.common.constants :refer [relationship-types]]))

(s/def ::id string?)
(s/def ::system_id string?)
(s/def ::type #(contains? relationship-types (name %)))
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

(defn make-relationship
  "Create a new Relationship with the given attributes"
  [attrs]
  (let [relationship (merge
                      {:id (str (random-uuid))
                       :relationship :unknown
                       :notes nil}
                      attrs)]
    (if (s/valid? ::relationship relationship)
      relationship
      (throw (ex-info "Invalid Relationship data"
                      {:type ::invalid-relationship
                       :spec-error (s/explain-data ::relationship relationship)
                       :data relationship})))))
