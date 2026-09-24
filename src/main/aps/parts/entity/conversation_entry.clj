(ns aps.parts.entity.conversation-entry
  "Conversation entries (ADR-0018): what was said or done in the
   conversation with a Part. Wraps the bitemporal layer.

   An entry belongs to the Session whose range holds its first
   valid-time (derived, ADR-0014 — no session column). Like notes, it can
   be edited or retracted from the present at any time; a sequenced write
   never changes what a past Session shows."
  (:require
   [aps.parts.common.models.conversation-entry :as model]
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]))

(defn- validate-part!
  "The entry's Part must currently exist in the entry's Map. `live-rows`
   (not `as-of-now`) so a Part created earlier in the same batch counts."
  [tx {:keys [map_id part_id]}]
  (when-not (seq (bt/live-rows tx :parts [:and
                                          [:= :map_id map_id]
                                          [:= :id part_id]]))
    (throw (ex-info "Conversation entry's Part is not a Part in this Map"
                    {:type :validation :part_id part_id :map_id map_id}))))

(defn create!
  "Add an entry. `data` carries the envelope `:id` (change-events always do)."
  [data actor-id tx]
  (let [entry (-> (model/make-conversation-entry data)
                  (db/coerce-uuid-keys [:id :map_id :part_id]))]
    (validate-part! tx entry)
    (bt/insert! tx :conversation_entries entry {:actor-id (db/->uuid actor-id)})))

(defn update!
  "Edit an entry's speaker or text. Scoped to `map-id`."
  [id data actor-id tx map-id]
  (model/validate-update data)
  (bt/update! tx :conversation_entries (db/->uuid id) data
              {:actor-id (db/->uuid actor-id)
               :scope    (db/map-scope map-id)}))

(defn delete!
  "Retract an entry. Scoped to `map-id` — an entry in another Map is
   not-found and nothing is retracted."
  [id actor-id tx map-id]
  (let [result (bt/retract! tx :conversation_entries (db/->uuid id)
                            {:actor-id (db/->uuid actor-id)
                             :scope    (db/map-scope map-id)})]
    {:id id :deleted (:retracted result)}))

(defn retract-for-part!
  "Retract every live entry of Part `part-id` — the Part is being
   retracted in the same transaction, so no entry outlives it in the
   present (ADR-0018). History keeps both."
  [tx part-id actor-id]
  (doseq [e (bt/live-rows tx :conversation_entries [:= :part_id (db/->uuid part-id)])]
    (bt/retract! tx :conversation_entries (:id e) {:actor-id (db/->uuid actor-id)})))
