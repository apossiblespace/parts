(ns aps.parts.common.models.relationship
  (:require
   [aps.parts.common.constants :refer [relationship-types]]
   [aps.parts.common.observe :as o]
   [aps.parts.common.utils :refer [validate-spec]]
   [clojure.spec.alpha :as s]))

(s/def ::id (s/or :string string? :uuid uuid?))
(s/def ::map_id (s/or :string string? :uuid uuid?))
(s/def ::type #(contains? relationship-types %))
(s/def ::source_id (s/or :string string? :uuid uuid?))
(s/def ::target_id (s/or :string string? :uuid uuid?))
(s/def ::notes (s/nilable string?))
(s/def ::intensity (s/and number? #(<= 0 % 100)))

(s/def ::relationship
  (s/keys :req-un [::map_id
                   ::type
                   ::source_id
                   ::target_id]
          :opt-un [::id
                   ::notes
                   ::intensity]))

(def spec
  "Relationship model spec for reuse outside of the namespace"
  ::relationship)

(defn make-relationship
  "Create a new Relationship with the given attributes. 
   In ClojureScript (frontend), generates a string UUID for :id. 
   In Clojure (backend), :id is set by the database layer."
  [attrs]
  (let [base         {:type      "unknown"
                      :notes     nil
                      :intensity 0}
        relationship #?(:cljs (merge {:id (str (random-uuid))} base attrs)
                        :clj (merge base attrs))]
    (o/debug "[make-relationship]" relationship)
    (validate-spec ::relationship relationship)
    relationship))

(defn- no-identity-keys?
  "A Relationship's id and map_id are identity — an update can't change them."
  [attrs]
  (not-any? #{:id :map_id} (keys attrs)))

(s/def ::relationship-update
  (s/and (s/keys :opt-un [::type
                          ::source_id
                          ::target_id
                          ::notes
                          ::intensity])
         no-identity-keys?))

(defn validate-update
  "Validate a partial relationship update map. Any fields present must conform to their specs."
  [attrs]
  (validate-spec ::relationship-update attrs))

(defn can-connect?
  "Return true if a new Relationship from `source-id` to `target-id` can be
   added to `relationships`. Blocks self-loops (A->A) — a Part cannot
   relate to itself — and any duplicate (source_id, target_id): only one
   relationship is allowed per ordered pair. Reverse direction (A->B
   alongside B->A) stays allowed."
  [relationships source-id target-id]
  (and (not= source-id target-id)
       (not-any? (fn [{existing-source :source_id
                       existing-target :target_id}]
                   (and (= existing-source source-id)
                        (= existing-target target-id)))
                 relationships)))
