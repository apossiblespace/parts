(ns aps.parts.common.models.part
  (:require
   [aps.parts.common.constants :refer [part-labels part-max-size
                                       part-min-size part-types]]
   [aps.parts.common.observe :as o]
   [aps.parts.common.utils :refer [validate-spec]]
   [clojure.spec.alpha :as s]))

(s/def ::id (s/or :string string? :uuid uuid?))
(s/def ::map_id (s/or :string string? :uuid uuid?))
(s/def ::type part-types)
(s/def ::label string?)
(s/def ::description (s/nilable string?))
(s/def ::position_x int?)
(s/def ::position_y int?)
(s/def ::width (s/nilable (s/and int? #(<= part-min-size % part-max-size))))
(s/def ::height (s/nilable (s/and int? #(<= part-min-size % part-max-size))))
(s/def ::notes (s/nilable string?))

;; Body location — where in the client's body a Part is felt (its somatic
;; locus). A structured point on a body silhouette, never free text (see
;; ADR-0013): which figure (`:view`) plus normalized 0..1 coordinates within
;; it. `:view` is a string (not a keyword) so it round-trips through JSONB
;; storage unchanged. One point, one view — richer geometry would extend this
;; value, not replace it.
(defn- normalized-coord?
  "A coordinate within a silhouette figure: a number in [0, 1]."
  [n]
  (and (number? n) (<= 0 n 1)))

(s/def ::body_location
  (s/nilable
   (s/and map?
          #(= #{:view :x :y} (set (keys %)))
          (comp #{"front" "back"} :view)
          #(normalized-coord? (:x %))
          #(normalized-coord? (:y %)))))

(s/def ::part
  (s/keys :req-un [::map_id
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
  (let [type  (or (:type attrs) "unknown")
        label (or (:label attrs) (get-in part-labels [(keyword type) :label]))
        base  {:type       type
               :label      label
               :position_x 0
               :position_y 0
               :notes      nil}
        part  (-> #?(:cljs (merge {:id (str (random-uuid))} base attrs)
                     :clj  (merge base attrs))
                  (update :position_x int)
                  (update :position_y int))]
    (o/debug "[make-part]" part)
    (validate-spec ::part part)
    part))

(defn- no-identity-keys?
  "A Part's id and map_id are identity — an update can't change them."
  [attrs]
  (not-any? #{:id :map_id} (keys attrs)))

(s/def ::part-update
  (s/and (s/keys :opt-un [::type
                          ::label
                          ::position_x
                          ::position_y
                          ::description
                          ::width
                          ::height
                          ::body_location
                          ::notes])
         no-identity-keys?))

(defn validate-update
  "Validate a partial part update map. Any fields present must conform to their specs."
  [attrs]
  (validate-spec ::part-update attrs))
