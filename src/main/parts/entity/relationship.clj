(ns parts.entity.relationship
  "An relationship between two parts is one of the components of a system map
  (see parts.entity.system)"
  (:require
   [clojure.spec.alpha :as s]
   [parts.common.constants :refer [relationship-types]]
   [parts.utils :refer [validate-spec]]
   [parts.db :as db]))

(s/def ::id string?)
(s/def ::system_id string?)
(s/def ::type relationship-types)
(s/def ::source_id string?)
(s/def ::target_id string?)
(s/def ::notes (s/nilable string?))

(s/def ::relationship (s/keys :req-un [::system_id ::source_id ::target_id ::type]
                              :opt-un [::id ::notes]))

(defn create-relationship!
  "Create a new relationship in a system"
  [data]
  (validate-spec ::relationship data)
  (db/insert! :relationships data))

(defn get-relationship
  "Get an relationship by ID"
  [id]
  (if-let [relationship (db/query-one
                         (db/sql-format
                          {:select [:*]
                           :from [:relationships]
                           :where [:= :id id]}))]
    relationship
    (throw (ex-info "Relationship not found" {:type :not-found :id id}))))

(defn update-relationship!
  "Update an relationship"
  [id data]
  (validate-spec ::relationship (assoc data :id id))
  (let [updated (db/update! :relationships data [:= :id id])]
    (if (seq updated)
      (first updated)
      (throw (ex-info "Relationship not found" {:type :not-found :id id})))))

(defn delete-relationship!
  "Delete an relationship. Returns a map with :id and :deleted keys, where :deleted is
  true if the relationship was found and deleted."
  [id]
  (let [result (db/delete! :relationships [:= :id id])
        count (or (:next.jdbc/update-count (first result)) 0)]
    {:id id :deleted (pos? count)}))
