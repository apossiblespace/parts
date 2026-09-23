(ns aps.parts.entity.part
  "A part is one of the components of a Map. Wraps the bitemporal
   layer; entity callers never see `valid_at` or `sys_period`."
  (:require
   [aps.parts.common.models.part :as model]
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [aps.parts.entity.conversation-entry :as conversation-entry]
   [aps.parts.entity.relationship :as relationship]))

(defn create!
  "Create a new part. Requires `actor-id` (the user making the change).
   Optional `tx` lets the caller participate in a surrounding transaction."
  ([data actor-id] (create! data actor-id db/datasource))
  ([data actor-id tx]
   (let [part (-> (model/make-part data)
                  (update :id #(or % (random-uuid)))
                  (db/coerce-uuid-keys [:id :map_id]))]
     (bt/insert! tx :parts part {:actor-id (db/->uuid actor-id)}))))

(defn fetch
  "Get a part by ID (current valid + current believed)."
  [id]
  (if-let [part (first (bt/as-of-now db/datasource :parts
                                     [:= :id (db/->uuid id)]))]
    part
    (throw (ex-info "Part not found" {:type :not-found :id id}))))

(defn update!
  "Update a part. Requires `actor-id`. When `map-id` is given (the API path),
   the update is scoped to that Map — a Part in another Map is not-found."
  ([id data actor-id] (update! id data actor-id db/datasource nil))
  ([id data actor-id tx] (update! id data actor-id tx nil))
  ([id data actor-id tx map-id]
   (model/validate-update data)
   (bt/update! tx :parts (db/->uuid id) data
               {:actor-id (db/->uuid actor-id)
                :scope    (db/map-scope map-id)})))

(defn delete!
  "Retract a part — it no longer exists from now on. Past history is preserved.
   Its Relationships and Conversation entries are retracted with it, in
   the same transaction, so no child outlives the Part in the present.
   `tx` must be an open transaction: the cascade is atomic only inside one.
   Scoped to `map-id` — a Part in another Map is not-found and nothing is
   retracted."
  [id actor-id tx map-id]
  (let [result (bt/retract! tx :parts (db/->uuid id)
                            {:actor-id (db/->uuid actor-id)
                             :scope    (db/map-scope map-id)})]
    (when (:retracted result)
      (relationship/retract-for-part! tx id actor-id)
      (conversation-entry/retract-for-part! tx id actor-id))
    {:id id :deleted (:retracted result)}))
