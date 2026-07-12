(ns aps.parts.entity.relationship
  "A relationship between two parts. Wraps the bitemporal layer."
  (:require
   [aps.parts.common.models.relationship :as model]
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]))

(defn- map-scope
  "WHERE fragment confining a write to Relationships of `map-id`, or nil when
   no Map is given. The API path always supplies it so a caller can only
   touch Relationships in the Map they're authorised for; a Relationship in
   another Map reads as not-found."
  [map-id]
  (when map-id [:= :map_id (db/->uuid map-id)]))

(defn- validate-endpoints!
  "Both ends of a relationship must be Parts that currently exist in the
   *same* Map as the relationship — otherwise a caller could draw an edge to
   a Part in another therapist's Map. `rel` has `:map_id`, `:source_id` and
   `:target_id` already coerced to UUIDs; `tx` is the surrounding transaction
   so Parts created earlier in the same batch are visible. Throws `ex-info`
   with `:type :validation` when an endpoint is missing or lives in a
   different Map."
  [tx {:keys [map_id source_id target_id]}]
  ;; A Part cannot relate to itself — meaningless in the domain, and the
  ;; canvas has no drawable curve for it. The client's `can-connect?`
  ;; blocks it too; this is the enforcement of record.
  (when (= source_id target_id)
    (throw (ex-info "A Part cannot relate to itself"
                    {:type :validation :id source_id :map_id map_id})))
  ;; `live-rows`, not `as-of-now`: the endpoint Parts are usually created
  ;; earlier in this same change-batch transaction, where the now()-based
  ;; reader can't see them (Postgres freezes now() at transaction start).
  (let [present (->> (bt/live-rows tx :parts
                                   [:and
                                    [:= :map_id map_id]
                                    [:in :id [source_id target_id]]])
                     (into #{} (map :id)))]
    (doseq [[role id] [[:source_id source_id] [:target_id target_id]]]
      (when-not (contains? present id)
        (throw (ex-info "Relationship endpoint is not a Part in this Map"
                        {:type :validation :endpoint role :id id :map_id map_id}))))))

(defn create!
  ([data actor-id] (create! data actor-id db/datasource))
  ([data actor-id tx]
   (let [rel (-> (model/make-relationship data)
                 (update :id #(or % (random-uuid)))
                 (db/coerce-uuid-keys [:id :map_id :source_id :target_id]))]
     (validate-endpoints! tx rel)
     (bt/insert! tx :relationships rel {:actor-id (db/->uuid actor-id)}))))

(defn fetch
  [id]
  (if-let [r (first (bt/as-of-now db/datasource :relationships
                                  [:= :id (db/->uuid id)]))]
    r
    (throw (ex-info "Relationship not found" {:type :not-found :id id}))))

(defn update!
  "Update a relationship. When `map-id` is given (the API path), scoped to
   that Map — a Relationship in another Map is not-found."
  ([id data actor-id] (update! id data actor-id db/datasource nil))
  ([id data actor-id tx] (update! id data actor-id tx nil))
  ([id data actor-id tx map-id]
   (model/validate-update data)
   (bt/update! tx :relationships (db/->uuid id) data
               {:actor-id (db/->uuid actor-id)
                :scope    (map-scope map-id)})))

(defn delete!
  "Retract a relationship. When `map-id` is given (the API path), scoped to
   that Map — a Relationship in another Map is not-found and nothing is
   retracted."
  ([id actor-id] (delete! id actor-id db/datasource nil))
  ([id actor-id tx] (delete! id actor-id tx nil))
  ([id actor-id tx map-id]
   (let [result (bt/retract! tx :relationships (db/->uuid id)
                             {:actor-id (db/->uuid actor-id)
                              :scope    (map-scope map-id)})]
     {:id id :deleted (:retracted result)})))
