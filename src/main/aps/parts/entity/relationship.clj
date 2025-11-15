(ns aps.parts.entity.relationship
  "An relationship between two parts is one of the components of a system map
  (see aps.parts.entity.system)"
  (:require
   [aps.parts.common.models.relationship :as model]
   [aps.parts.common.utils :refer [validate-spec]]
   [aps.parts.db :as db]))

(defn create!
  "Create a new relationship in a system"
  [data]
  (let [relationship (model/make-relationship data)]
    (db/insert! :relationships relationship)))

(defn fetch
  "Get an relationship by ID"
  [id]
  (if-let [relationship (db/query-one
                         (db/sql-format
                          {:select [:*]
                           :from   [:relationships]
                           :where  [:= :id id]}))]
    relationship
    (throw (ex-info "Relationship not found" {:type :not-found :id id}))))

(defn update!
  "Update an relationship"
  [id data]
  (validate-spec model/spec (assoc data :id id))
  (let [updated (db/update! :relationships data [:= :id id])]
    (if (seq updated)
      (first updated)
      (throw (ex-info "Relationship not found" {:type :not-found :id id})))))

(defn delete!
  "Delete an relationship. Returns a map with :id and :deleted keys, where :deleted is
  true if the relationship was found and deleted."
  [id]
  (let [result (db/delete! :relationships [:= :id id])
        count  (or (:next.jdbc/update-count (first result)) 0)]
    {:id id :deleted (pos? count)}))
