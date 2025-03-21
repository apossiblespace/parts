(ns parts.entity.system
  "Entity representing a system map, including its nodes and edges.
   Systems are owned by users and contain a collection of nodes and their
   relationships (edges)."
  (:require
   [clojure.spec.alpha :as s]
   [parts.utils :refer [validate-spec]]
   [parts.db :as db]))

(s/def ::id string?)
(s/def ::title string?)
(s/def ::owner_id string?)
(s/def ::viewport_settings (s/nilable string?))

(s/def ::system (s/keys :req-un [::title ::owner_id]
                        :opt-un [::id ::viewport_settings]))

(defn create-system!
  "Create a new system"
  [data]
  (validate-spec ::system data)
  (db/insert! :systems data))

(defn get-system
  "Get a system by ID, including all its nodes and edges.

   Returns a map containing:
   - Basic system information (id, title, etc.)
   - :nodes vector containing all nodes
   - :edges vector containing all edges

   Example:
   ```
   (get-system \"123\")
   ;; => {:id \"123\"
   ;;     :title \"My System\"
   ;;     :owner_id \"user-456\"
   ;;     :nodes [{:id \"n1\" :type \"manager\" ...}]
   ;;     :edges [{:id \"e1\" :source_id \"n1\" ...}]}
   ```"
  [id]
  (if-let [system (db/query-one
                   (db/sql-format
                    {:select [:*]
                     :from [:systems]
                     :where [:= :id id]}))]
    (let [nodes (db/query
                 (db/sql-format
                  {:select [:*]
                   :from [:nodes]
                   :where [:= :system_id id]}))
          edges (db/query
                 (db/sql-format
                  {:select [:*]
                   :from [:edges]
                   :where [:= :system_id id]}))]
      (assoc system
             :nodes nodes
             :edges edges))
    (throw (ex-info "System not found" {:type :not-found :id id}))))

(defn list-systems
  "List all systems for a user"
  [owner-id]
  (db/query
   (db/sql-format
    {:select [:*]
     :from [:systems]
     :where [:= :owner_id owner-id]})))

(defn update-system!
  "Update a system"
  [id data]
  (validate-spec ::system (assoc data :id id))
  (let [updated (db/update! :systems
                            (assoc data :last_modified [:raw "CURRENT_TIMESTAMP"])
                            [:= :id id])]
    (if (seq updated)
      (first updated)
      (throw (ex-info "System not found" {:type :not-found :id id})))))

(defn delete-system!
  "Delete a system. Returns a map with :id and :deleted keys,
   where :deleted is true if the system was found and deleted."
  [id]
  (let [result (db/delete! :systems [:= :id id])
        count (or (:next.jdbc/update-count (first result)) 0)]
    {:id id :deleted (pos? count)}))
