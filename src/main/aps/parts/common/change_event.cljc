(ns aps.parts.common.change-event
  "A change-event is the intent to mutate a System's contents.

   A change-event is `{:entity :type :id :data}`: a keyworded entity (`:part` /
   `:relationship`), a keyworded operation (`:create` / `:update` / `:remove`),
   the entity id, and a per-operation `:data` payload.

   This module is the seam the change-event crosses client-to-server: it owns
   the canonical shape and validation here, named constructors (producer-side)
   and a `parse` trust gate (consumer-side) in later commits. `:data` validation
   composes the attribute specs already registered by `common.models.*` — the
   change-event module never constructs domain entities, it only envelopes them
   for transport.

   Change-events cover committed mutations only, what goes through the
   all-or-nothing batch into the bitemporal record."
  (:require
   [aps.parts.common.models.part :as part]
   [aps.parts.common.models.relationship :as relationship]
   [aps.parts.common.utils :as utils]
   [clojure.spec.alpha :as s]))

;; -- envelope --------------------------------------------------------------

(s/def ::entity #{:part :relationship})
(s/def ::type   #{:create :update :remove})
(s/def ::id     (s/or :string string? :uuid uuid?))
(s/def ::data   map?)

;; -- per-operation :data payloads -----------------------------------------
;; :data composes the attribute specs registered by `common.models.*`.
;; 
;; :data never carries :id (lives on the envelope) or :system_id (a property of
;; the batch).
;; 
;; `:create` requires the entity's mandatory attributes; `:update` is any
;; non-empty subset; `:remove` carries nothing.

(s/def ::part-create-data
  (s/keys :req-un [::part/type ::part/label ::part/position_x ::part/position_y]
          :opt-un [::part/description ::part/width ::part/height
                   ::part/notes ::part/body_location]))

(s/def ::part-update-data
  (s/and (s/keys :opt-un [::part/type ::part/label ::part/position_x ::part/position_y
                          ::part/description ::part/width ::part/height
                          ::part/notes ::part/body_location])
         seq))

(s/def ::relationship-create-data
  (s/keys :req-un [::relationship/type ::relationship/source_id ::relationship/target_id]
          :opt-un [::relationship/notes]))

(s/def ::relationship-update-data
  (s/and (s/keys :opt-un [::relationship/type ::relationship/source_id
                          ::relationship/target_id ::relationship/notes])
         seq))

(s/def ::remove-data (s/and map? empty?))

;; -- :data spec dispatch ---------------------------------------------------

(defn data-spec
  "The spec a change-event's `:data` must satisfy, given its `:entity` and
   `:type`. Returns nil for an unknown `[entity type]` combination. Reused
   by `parse` to validate and explain `:data` precisely."
  [{:keys [entity type]}]
  (case [entity type]
    [:part :create]         ::part-create-data
    [:part :update]         ::part-update-data
    [:part :remove]         ::remove-data
    [:relationship :create] ::relationship-create-data
    [:relationship :update] ::relationship-update-data
    [:relationship :remove] ::remove-data
    nil))

;; -- the canonical change-event -------------------------------------------

(s/def ::change-event
  (s/and (s/keys :req-un [::entity ::type ::id ::data])
         (fn data-conforms? [ce]
           (when-let [spec (data-spec ce)]
             (s/valid? spec (:data ce))))))

;; -- constructors ----------------------------------------------------------
;; Producer-side. Each validates eagerly, so a bad event never reaches the queue.

(defn- build [entity type id data]
  (let [event {:entity entity :type type :id id :data data}]
    (utils/validate-spec ::change-event event)
    event))

(defn part-create
  "Change-event creating a Part with `attrs`."
  [id attrs]
  (build :part :create id attrs))

(defn part-update
  "Change-event updating a Part with `attrs`."
  [id attrs]
  (build :part :update id attrs))

(defn part-moved
  "Change-event for a Part moved to (`x`, `y`). Coordinates are coerced to
  ints."
  [id x y]
  (build :part :update id {:position_x (int x) :position_y (int y)}))

(defn part-remove
  "Change-event retracting a Part."
  [id]
  (build :part :remove id {}))

(defn relationship-create
  "Change-event creating a Relationship with `attrs`."
  [id attrs]
  (build :relationship :create id attrs))

(defn relationship-update
  "Change-event updating a Relationship with `attrs`."
  [id attrs]
  (build :relationship :update id attrs))

(defn relationship-remove
  "Change-event retracting a Relationship."
  [id]
  (build :relationship :remove id {}))
