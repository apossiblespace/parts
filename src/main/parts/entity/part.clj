(ns parts.entity.part
  "A part is one of the components of a system map (see parts.entity.system)"
  (:require
   [parts.common.models.part :as model]
   [parts.common.utils :refer [validate-spec]]
   [parts.db :as db]))

(defn create!
  "Create a new part in a system"
  [data]
  (let [part (model/make-part data)]
    (db/insert! :parts part)))

(defn fetch
  "Get a part by ID"
  [id]
  (if-let [part (db/query-one
                 (db/sql-format
                  {:select [:*]
                   :from [:parts]
                   :where [:= :id id]}))]
    part
    (throw (ex-info "Part not found" {:type :not-found :id id}))))

(defn update!
  "Update a part"
  [id data]
  (validate-spec model/spec (assoc data :id id))
  (let [updated (db/update! :parts data [:= :id id])]
    (if (seq updated)
      (first updated)
      (throw (ex-info "Part not found" {:type :not-found :id id})))))

(defn delete!
  "Delete a part. Returns a map with :id and :deleted keys, where :deleted is
  true if the part was found and deleted."
  [id]
  (let [result (db/delete! :parts [:= :id id])
        count (or (:next.jdbc/update-count (first result)) 0)]
    {:id id :deleted (pos? count)}))
