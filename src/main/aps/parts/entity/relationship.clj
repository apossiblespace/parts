(ns aps.parts.entity.relationship
  "A relationship between two parts. Wraps the bitemporal layer."
  (:require
   [aps.parts.common.models.relationship :as model]
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]))

(defn- with-uuid-keys [r]
  (cond-> r
    (:id r)        (update :id db/->uuid)
    (:system_id r) (update :system_id db/->uuid)
    (:source_id r) (update :source_id db/->uuid)
    (:target_id r) (update :target_id db/->uuid)))

(defn create!
  ([data actor-id] (create! data actor-id db/datasource))
  ([data actor-id tx]
   (let [rel (-> (model/make-relationship data)
                 (update :id #(or % (random-uuid)))
                 with-uuid-keys)]
     (bt/insert! tx :relationships rel {:actor-id (db/->uuid actor-id)}))))

(defn fetch
  [id]
  (if-let [r (first (bt/as-of-now db/datasource :relationships
                                  [:= :id (db/->uuid id)]))]
    r
    (throw (ex-info "Relationship not found" {:type :not-found :id id}))))

(defn update!
  ([id data actor-id] (update! id data actor-id db/datasource))
  ([id data actor-id tx]
   (model/validate-update data)
   (bt/update! tx :relationships (db/->uuid id) data
               {:actor-id (db/->uuid actor-id)})))

(defn delete!
  ([id actor-id] (delete! id actor-id db/datasource))
  ([id actor-id tx]
   (let [result (bt/retract! tx :relationships (db/->uuid id)
                             {:actor-id (db/->uuid actor-id)})]
     {:id id :deleted (:retracted result)})))
