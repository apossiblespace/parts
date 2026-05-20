(ns aps.parts.entity.map
  "A Map containing parts and relationships. Owned by a user.

   The `maps` table itself holds identity only (id, owner_id, created_at,
   deleted_at, actor_id). Its metadata (title now; future sharing /
   permissions / client_id) lives in the bitemporal `map_metadata` table.
   Parts and relationships are bitemporal too.

   Callers of this entity API never see the split — `fetch` and `index`
   merge the metadata back into the response, and `create!` / `update!`
   route their inputs to the right table."
  (:require
   [aps.parts.common.models.map :as model]
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [com.brunobonacci.mulog :as mulog]
   [next.jdbc :as jdbc]))

(defn- current-metadata
  "Return the current `map_metadata` row for a map, or nil."
  [ds map-uuid]
  (first (bt/as-of-now ds :map_metadata [:= :map_id map-uuid])))

(defn create!
  "Create a new map: an identity row in `maps` plus an initial metadata
   row in `map_metadata`, atomically. Returns the merged map (the shape
   callers expect)."
  ([data actor-id]
   (db/with-transaction
     (fn [tx] (create! data actor-id tx))))
  ([data actor-id tx]
   (let [validated (model/make-map data)
         actor     (db/->uuid actor-id)
         the-map   (db/insert! :maps
                               {:owner_id (db/->uuid (:owner_id validated))
                                :actor_id actor}
                               tx)]
     (bt/insert! tx :map_metadata
                 {:id     (random-uuid)
                  :map_id (:id the-map)
                  :title  (:title validated)}
                 {:actor-id actor})
     (assoc the-map :title (:title validated)))))

(defn fetch-identity
  "Get an alive map's identity row (id, owner_id, ...) by ID, or nil.
   The cheap reader for ownership checks — no metadata, parts, or
   relationships joined in. See ADR-0002."
  [id]
  (db/query-one
   (db/sql-format
    {:select [:*]
     :from   [:maps]
     :where  [:and
              [:= :id (db/->uuid id)]
              [:= :deleted_at nil]]})))

(defn fetch
  "Get an alive map by ID, including its current title and current parts
   and relationships."
  [id]
  (let [uuid-id (db/->uuid id)
        the-map (fetch-identity uuid-id)]
    (when-not the-map
      (throw (ex-info "Map not found" {:type :not-found :id id})))
    (assoc the-map
           :title         (:title (current-metadata db/datasource uuid-id))
           :parts         (vec (bt/as-of-now db/datasource :parts
                                             [:= :map_id uuid-id]))
           :relationships (vec (bt/as-of-now db/datasource :relationships
                                             [:= :map_id uuid-id])))))

(defn index
  "List a user's alive maps (with current title, without parts /
   relationships). One query for maps, one for all their current
   metadata; in-memory join."
  [owner-id]
  (let [maps (db/query
              (db/sql-format
               {:select [:*]
                :from   [:maps]
                :where  [:and
                         [:= :owner_id (db/->uuid owner-id)]
                         [:= :deleted_at nil]]}))]
    (if (empty? maps)
      []
      (let [meta-by-map (->> (bt/as-of-now db/datasource :map_metadata
                                           [:in :map_id (mapv :id maps)])
                             (group-by :map_id))]
        (mapv (fn [m]
                (assoc m :title (-> meta-by-map (get (:id m)) first :title)))
              maps)))))

(defn update!
  "Update a map's metadata (currently just `title`).

   Resolves the map's current metadata row, then calls `bt/update!`. The
   `:owner_id` is identity and cannot be changed — it's silently dropped
   from the input if present."
  ([id data actor-id]
   (db/with-transaction
     (fn [tx] (update! id data actor-id tx))))
  ([id data actor-id tx]
   (model/validate-update data)
   (let [uuid-id  (db/->uuid id)
         metadata (current-metadata tx uuid-id)
         _        (when-not metadata
                    (throw (ex-info "Map not found" {:type :not-found :id id})))
         changes  (select-keys data [:title])
         result   (bt/update! tx :map_metadata (:id metadata) changes
                              {:actor-id (db/->uuid actor-id)})]
     (-> {:id    uuid-id
          :title (:title result)}
         (merge (dissoc result :id :map_id))))))

(defn- delete-impl! [id actor-id tx]
  (mulog/log ::delete-map-start :map-id id)
  (let [uuid-id (db/->uuid id)
        actor   (db/->uuid actor-id)
        parts   (bt/as-of-now tx :parts [:= :map_id uuid-id])
        rels    (bt/as-of-now tx :relationships [:= :map_id uuid-id])
        meta    (current-metadata tx uuid-id)]
    ;; Child cascade: mirror in `db.erasure/purge-account!`.
    (doseq [r rels]
      (bt/retract! tx :relationships (:id r) {:actor-id actor}))
    (doseq [p parts]
      (bt/retract! tx :parts (:id p) {:actor-id actor}))
    (when meta
      (bt/retract! tx :map_metadata (:id meta) {:actor-id actor}))
    (jdbc/execute! tx
                   (db/sql-format
                    {:update :maps
                     :set    {:deleted_at [:now]
                              :actor_id   actor}
                     :where  [:= :id uuid-id]}))
    (mulog/log ::delete-map-complete
               :map-id id
               :parts-retracted (count parts)
               :relationships-retracted (count rels))
    {:id                    id
     :success               true
     :parts-deleted         (count parts)
     :relationships-deleted (count rels)}))

(defn delete!
  "Soft-delete a map. Retracts all bitemporal children (parts,
   relationships, metadata) from current view; sets `maps.deleted_at`.
   Past history is preserved.

   For permanent erasure (the right-to-be-forgotten path) see
   `aps.parts.db.erasure/purge-account!`."
  ([id actor-id]
   (jdbc/with-transaction [tx db/datasource]
     (delete-impl! id actor-id tx)))
  ([id actor-id tx]
   (delete-impl! id actor-id tx)))
