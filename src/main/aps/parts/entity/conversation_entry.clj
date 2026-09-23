(ns aps.parts.entity.conversation-entry
  "Conversation entries (ADR-0018): what was said or done in the
   conversation with a Part. Wraps the bitemporal layer.

   An entry belongs to the Session whose range holds its first
   valid-time (derived, ADR-0014 — no session column). It can be edited or
   retracted only while that Session is the active one; after that it is
   part of the read-only past."
  (:require
   [aps.parts.common.models.conversation-entry :as model]
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [aps.parts.entity.session :as session]))

(defn- validate-part!
  "The entry's Part must currently exist in the entry's Map. `live-rows`
   (not `as-of-now`) so a Part created earlier in the same batch counts."
  [tx {:keys [map_id part_id]}]
  (when-not (seq (bt/live-rows tx :parts [:and
                                          [:= :map_id map_id]
                                          [:= :id part_id]]))
    (throw (ex-info "Conversation entry's Part is not a Part in this Map"
                    {:type :validation :part_id part_id :map_id map_id}))))

(defn- require-active-session!
  "Throws unless entry `id` in `map-id` first appeared in the active
   Session; not-found when the entry is not in this Map."
  [tx map-id id]
  (let [scope    [:and [:= :id id] [:= :map_id (db/->uuid map-id)]]
        first-at (get (bt/first-appearances tx :conversation_entries scope) id)]
    (when-not first-at
      (throw (ex-info "Conversation entry not found" {:type :not-found :id id})))
    (session/require-active! tx map-id first-at)))

(defn create!
  "Add an entry. `data` carries the envelope `:id` (change-events always do)."
  [data actor-id tx]
  (let [entry (-> (model/make-conversation-entry data)
                  (db/coerce-uuid-keys [:id :map_id :part_id]))]
    (validate-part! tx entry)
    (bt/insert! tx :conversation_entries entry {:actor-id (db/->uuid actor-id)})))

(defn update!
  "Edit an entry's speaker or text. Scoped to `map-id`; only while the
   entry's Session is active."
  [id data actor-id tx map-id]
  (model/validate-update data)
  (let [uuid (db/->uuid id)]
    (require-active-session! tx map-id uuid)
    (bt/update! tx :conversation_entries uuid data
                {:actor-id (db/->uuid actor-id)
                 :scope    (db/map-scope map-id)})))

(defn delete!
  "Retract an entry. Scoped to `map-id`; only while the entry's Session is
   active."
  [id actor-id tx map-id]
  (let [uuid (db/->uuid id)]
    (require-active-session! tx map-id uuid)
    (let [result (bt/retract! tx :conversation_entries uuid
                              {:actor-id (db/->uuid actor-id)
                               :scope    (db/map-scope map-id)})]
      {:id id :deleted (:retracted result)})))

(defn retract-for-part!
  "Retract every live entry of Part `part-id` — the Part is being
   retracted in the same transaction, so no entry outlives it in the
   present (ADR-0018). History keeps both. Not session-gated: deleting a
   Part is allowed, and its whole conversation goes with it."
  [tx part-id actor-id]
  (doseq [e (bt/live-rows tx :conversation_entries [:= :part_id (db/->uuid part-id)])]
    (bt/retract! tx :conversation_entries (:id e) {:actor-id (db/->uuid actor-id)})))
