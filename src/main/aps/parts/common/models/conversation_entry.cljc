(ns aps.parts.common.models.conversation-entry
  "A Conversation entry (ADR-0018): one thing said or done in the
   conversation with one Part — a quote, a reaction, or an action."
  (:require
   [aps.parts.common.constants :refer [conversation-speakers max-text-length]]
   [aps.parts.common.utils :refer [validate-spec]]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]))

(s/def ::id (s/or :string string? :uuid uuid?))
(s/def ::map_id (s/or :string string? :uuid uuid?))
(s/def ::part_id (s/or :string string? :uuid uuid?))
(s/def ::speaker (set conversation-speakers))
(s/def ::text (s/and string?
                     (complement str/blank?)
                     #(<= (count %) max-text-length)))

(s/def ::conversation-entry
  (s/keys :req-un [::map_id ::part_id ::speaker ::text]
          :opt-un [::id]))

(defn make-conversation-entry
  "Validate a new entry. In ClojureScript an :id is generated when absent;
   on the backend the entity layer assigns it."
  [attrs]
  (let [entry #?(:cljs (merge {:id (str (random-uuid))} attrs)
                 :clj attrs)]
    (validate-spec ::conversation-entry entry)
    entry))

(s/def ::conversation-entry-update
  (s/and (s/keys :opt-un [::speaker ::text])
         ;; Identity and the Part it belongs to are fixed at creation.
         #(not-any? #{:id :map_id :part_id} (keys %))))

(defn validate-update
  [attrs]
  (validate-spec ::conversation-entry-update attrs))
