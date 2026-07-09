(ns aps.parts.common.models.session
  "A Session: a named marker on a Map's bitemporal timeline (ADR-0014).
   Deliberately thin — its only load-bearing datum is the anchor; membership
   is derived from content valid-times, never stamped."
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::id (s/or :string string? :uuid uuid?))
(s/def ::map_id (s/or :string string? :uuid uuid?))
(s/def ::ordinal pos-int?)
(s/def ::trigger (s/nilable string?))
(s/def ::anchor_valid_at some?)

(s/def ::session
  (s/keys :req-un [::map_id
                   ::ordinal
                   ::anchor_valid_at]
          :opt-un [::id
                   ::trigger]))

(def spec
  "Session model spec for reuse outside of the namespace"
  ::session)
