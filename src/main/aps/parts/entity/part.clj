(ns aps.parts.entity.part
  "A part is one of the components of a system map. Wraps the bitemporal
   layer; entity callers never see `valid_at` or `sys_period`."
  (:require
   [aps.parts.common.models.part :as model]
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]))

(defn create!
  "Create a new part. Requires `actor-id` (the user making the change).
   Optional `tx` lets the caller participate in a surrounding transaction."
  ([data actor-id] (create! data actor-id db/datasource))
  ([data actor-id tx]
   (let [part (-> (model/make-part data)
                  (update :id #(or % (random-uuid)))
                  (db/coerce-uuid-keys [:id :system_id]))]
     (bt/insert! tx :parts part {:actor-id (db/->uuid actor-id)}))))

(defn fetch
  "Get a part by ID (current valid + current believed)."
  [id]
  (if-let [part (first (bt/as-of-now db/datasource :parts
                                     [:= :id (db/->uuid id)]))]
    part
    (throw (ex-info "Part not found" {:type :not-found :id id}))))

(defn update!
  "Update a part. Requires `actor-id`."
  ([id data actor-id] (update! id data actor-id db/datasource))
  ([id data actor-id tx]
   (model/validate-update data)
   (bt/update! tx :parts (db/->uuid id) data
               {:actor-id (db/->uuid actor-id)})))

(defn delete!
  "Retract a part — it no longer exists from now on. Past history is preserved."
  ([id actor-id] (delete! id actor-id db/datasource))
  ([id actor-id tx]
   (let [result (bt/retract! tx :parts (db/->uuid id)
                             {:actor-id (db/->uuid actor-id)})]
     {:id id :deleted (:retracted result)})))
