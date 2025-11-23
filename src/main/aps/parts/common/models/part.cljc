(ns aps.parts.common.models.part
  (:require
   [aps.parts.common.constants :refer [part-types part-labels]]
   [aps.parts.common.utils :refer [validate-spec]]
   [clojure.spec.alpha :as s]))

(s/def ::id (s/or :string string? :uuid uuid?))
(s/def ::system_id (s/or :string string? :uuid uuid?))
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
  (s/keys :req-un [::system_id
                   ::type
                   ::label
                   ::position_x
                   ::position_y]
          :opt-un [::id
                   ::description
                   ::width
                   ::height
                   ::body_location
                   ::notes]))

(def spec
  "Part model spec for reuse outside of the namespace"
  ::part)

(defn make-part
  "Create a new Part with the given attributes. 
   In ClojureScript (frontend), generates a string UUID for :id. 
   In Clojure (backend), :id is set by the database layer."
  [attrs]
  (println "[make-part]" attrs)
  (let [type  (or (:type attrs) "unknown")
        label (or (:label attrs) (get-in part-labels [(keyword type) :label]))
        base  {:type       type
               :label      label
               :position_x 0
               :position_y 0
               :notes      nil}
        part  #?(:cljs (merge {:id (str (random-uuid))} base attrs)
                 :clj (merge base attrs))]
    (validate-spec ::part part)
    part))
