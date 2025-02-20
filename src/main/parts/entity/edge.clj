(ns parts.entity.edge
  "An edge is one of the components of a system map (see parts.entity.system),
   representing the relationship between two nodes (parts)."
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

(s/def ::edge (s/keys :req-un [::system_id ::source_id ::target_id ::type]
                      :opt-un [::id ::notes]))

(defn create-edge!
  "Create a new edge in a system"
  [data]
  (validate-spec ::edge data)
  (db/insert! :edges data))

(defn get-edge
  "Get an edge by ID"
  [id]
  (if-let [edge (db/query-one
                 (db/sql-format
                  {:select [:*]
                   :from [:edges]
                   :where [:= :id id]}))]
    edge
    (throw (ex-info "Edge not found" {:type :not-found :id id}))))

(defn update-edge!
  "Update an edge"
  [id data]
  (validate-spec ::edge (assoc data :id id))
  (let [updated (db/update! :edges data [:= :id id])]
    (if (seq updated)
      (first updated)
      (throw (ex-info "Edge not found" {:type :not-found :id id})))))

(defn delete-edge!
  "Delete an edge. Returns a map with :id and :deleted keys, where :deleted is
  true if the edge was found and deleted."
  [id]
  (let [result (db/delete! :edges [:= :id id])
        count (or (:next.jdbc/update-count (first result)) 0)]
    {:id id :deleted (pos? count)}))
