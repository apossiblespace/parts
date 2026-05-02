(ns aps.parts.entity.relationship
  "An relationship between two parts is one of the components of a system map
  (see aps.parts.entity.system)"
  (:require
   [aps.parts.common.models.relationship :as model]
   [aps.parts.db :as db]))

(defn create!
  "Create a new relationship in a system.
   Accepts an optional datasource-or-transaction to participate in a surrounding tx."
  ([data] (create! data db/datasource))
  ([data tx]
   (let [relationship (model/make-relationship data)]
     (db/insert! :relationships relationship tx))))

(defn fetch
  "Get an relationship by ID"
  [id]
  (if-let [relationship (db/query-one
                         (db/sql-format
                          {:select [:*]
                           :from   [:relationships]
                           :where  [:= :id (db/->uuid id)]}))]
    relationship
    (throw (ex-info "Relationship not found" {:type :not-found :id id}))))

(defn update!
  "Update an relationship"
  [id data]
  (model/validate-update data)
  (let [updated (db/update! :relationships data [:= :id (db/->uuid id)])]
    (if (seq updated)
      (first updated)
      (throw (ex-info "Relationship not found" {:type :not-found :id id})))))

(defn delete!
  "Delete an relationship. Returns a map with :id and :deleted keys, where :deleted is
  true if the relationship was found and deleted."
  [id]
  (let [result (db/delete! :relationships [:= :id (db/->uuid id)])
        count  (or (:next.jdbc/update-count (first result)) 0)]
    {:id id :deleted (pos? count)}))
