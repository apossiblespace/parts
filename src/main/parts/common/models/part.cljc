(ns parts.common.models.part
  (:require
   [clojure.spec.alpha :as s]
   [parts.common.utils :refer [validate-spec]]
   [parts.common.constants :refer [part-types part-labels]]))

(s/def ::id string?)
(s/def ::system_id string?)
(s/def ::type part-types)
(s/def ::label string?)
(s/def ::description (s/nilable string?))
(s/def ::position_x int?)
(s/def ::position_y int?)
(s/def ::width (s/nilable int?))
(s/def ::height (s/nilable int?))
(s/def ::notes (s/nilable string?))
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
                   ::body_location
                   ::notes]))

(def spec
  "Part model spec for reuse outside of the namespace"
  ::part)

(defn make-part
  "Create a new Part with the given attributes"
  [attrs]
  (println "[make-part]" attrs)
  (let [type (or (:type attrs) "unknown")
        label (or (:label attrs) (get-in part-labels [(keyword type) :label]))
        part (merge
              {:id (str (random-uuid))
               :type type
               :label label
               :position_x 0
               :position_y 0
               :notes nil}
              attrs)]
    (validate-spec ::part part)
    part))
