(ns parts.common.models.part
  (:require
   [clojure.spec.alpha :as s]
   [parts.common.constants :refer [part-types]]))

(s/def ::id string?)
(s/def ::system_id string?)
(s/def ::type #(contains? part-types (name %)))
(s/def ::label string?)
(s/def ::description (s/nilable string?))
(s/def ::position_x int?)
(s/def ::position_y int?)
(s/def ::width (s/nilable int?))
(s/def ::height (s/nilable int?))
(s/def ::body_location (s/nilable string?))

(s/def ::part
  (s/keys :req-un [::id
                   ::system_id
                   ::type
                   ::label
                   ::position_x
                   ::position_y]
          :opt-un [::description
                   ::width
                   ::height
                   ::body_location]))

(defn make-part
  "Create a new Part with the given attributes"
  [attrs]
  (let [part (merge
              {:id (str (random-uuid))
               :type :unknown
               :label "Unknonwn"
               :position_x 0
               :position_y 0}
              attrs)]
    (if (s/valid? ::part part)
      part
      (throw (ex-info "Invalid Part data"
                      {:type ::invalid-part
                       :spec-error (s/explain-data ::part part)
                       :data part})))))
