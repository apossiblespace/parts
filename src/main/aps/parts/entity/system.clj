(ns aps.parts.entity.system
  "A system map containing parts and relationships. Owned by a user.

   The `systems` table itself holds identity only (id, owner_id, created_at,
   deleted_at, actor_id). Its metadata (title now; future sharing /
   permissions / client_id) lives in the bitemporal `system_metadata` table.
   Parts and relationships are bitemporal too.

   Callers of this entity API never see the split — `fetch` and `index`
   merge the metadata back into the response, and `create!` / `update!`
   route their inputs to the right table."
  (:require
   [aps.parts.common.models.system :as model]
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [com.brunobonacci.mulog :as mulog]
   [next.jdbc :as jdbc]))

(defn- current-metadata
  "Return the current `system_metadata` row for a system, or nil."
  [ds system-uuid]
  (first (bt/as-of-now ds :system_metadata [:= :system_id system-uuid])))

(defn create!
  "Create a new system: an identity row in `systems` plus an initial metadata
   row in `system_metadata`, atomically. Returns the merged map (the shape
   callers expect)."
  ([data actor-id]
   (db/with-transaction
     (fn [tx] (create! data actor-id tx))))
  ([data actor-id tx]
   (let [validated (model/make-system data)
         actor     (db/->uuid actor-id)
         system    (db/insert! :systems
                               {:owner_id (db/->uuid (:owner_id validated))
                                :actor_id actor}
                               tx)]
     (bt/insert! tx :system_metadata
                 {:id        (random-uuid)
                  :system_id (:id system)
                  :title     (:title validated)}
                 {:actor-id actor})
     (assoc system :title (:title validated)))))

(defn fetch
  "Get an alive system by ID, including its current title and current parts
   and relationships."
  [id]
  (let [uuid-id (db/->uuid id)
        system  (db/query-one
                 (db/sql-format
                  {:select [:*]
                   :from   [:systems]
                   :where  [:and
                            [:= :id uuid-id]
                            [:= :deleted_at nil]]}))]
    (when-not system
      (throw (ex-info "System not found" {:type :not-found :id id})))
    (assoc system
           :title         (:title (current-metadata db/datasource uuid-id))
           :parts         (vec (bt/as-of-now db/datasource :parts
                                             [:= :system_id uuid-id]))
           :relationships (vec (bt/as-of-now db/datasource :relationships
                                             [:= :system_id uuid-id])))))

(defn index
  "List a user's alive systems (with current title, without parts /
   relationships). One query for systems, one for all their current
   metadata; in-memory join."
  [owner-id]
  (let [systems (db/query
                 (db/sql-format
                  {:select [:*]
                   :from   [:systems]
                   :where  [:and
                            [:= :owner_id (db/->uuid owner-id)]
                            [:= :deleted_at nil]]}))]
    (if (empty? systems)
      []
      (let [meta-by-system (->> (bt/as-of-now db/datasource :system_metadata
                                              [:in :system_id (mapv :id systems)])
                                (group-by :system_id))]
        (mapv (fn [s]
                (assoc s :title (-> meta-by-system (get (:id s)) first :title)))
              systems)))))

(defn update!
  "Update a system's metadata (currently just `title`).

   Resolves the system's current metadata row, then calls `bt/update!`. The
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
                    (throw (ex-info "System not found" {:type :not-found :id id})))
         changes  (select-keys data [:title])
         result   (bt/update! tx :system_metadata (:id metadata) changes
                              {:actor-id (db/->uuid actor-id)})]
     (-> {:id    uuid-id
          :title (:title result)}
         (merge (dissoc result :id :system_id))))))

(defn- delete-impl! [id actor-id tx]
  (mulog/log ::delete-system-start :system-id id)
  (let [uuid-id (db/->uuid id)
        actor   (db/->uuid actor-id)
        parts   (bt/as-of-now tx :parts [:= :system_id uuid-id])
        rels    (bt/as-of-now tx :relationships [:= :system_id uuid-id])
        meta    (current-metadata tx uuid-id)]
    (doseq [r rels]
      (bt/retract! tx :relationships (:id r) {:actor-id actor}))
    (doseq [p parts]
      (bt/retract! tx :parts (:id p) {:actor-id actor}))
    (when meta
      (bt/retract! tx :system_metadata (:id meta) {:actor-id actor}))
    (jdbc/execute! tx
                   (db/sql-format
                    {:update :systems
                     :set    {:deleted_at [:now]
                              :actor_id   actor}
                     :where  [:= :id uuid-id]}))
    (mulog/log ::delete-system-complete
               :system-id id
               :parts-retracted (count parts)
               :relationships-retracted (count rels))
    {:id                    id
     :success               true
     :parts-deleted         (count parts)
     :relationships-deleted (count rels)}))

(defn delete!
  "Soft-delete a system. Retracts all bitemporal children (parts,
   relationships, metadata) from current view; sets `systems.deleted_at`.
   Past history is preserved.

   For permanent erasure (the right-to-be-forgotten path) see
   `aps.parts.db.erasure/purge-account!`."
  ([id actor-id]
   (jdbc/with-transaction [tx db/datasource]
     (delete-impl! id actor-id tx)))
  ([id actor-id tx]
   (delete-impl! id actor-id tx)))
