(ns parts.entity.node
  "A node is one of the components of a system map (see parts.entity.system),
   representing a Part."
  (:require
   [clojure.spec.alpha :as s]
   [parts.common.constants :refer [part-types]]
   [parts.utils :refer [validate-spec]]
   [parts.db :as db]))

(s/def ::id string?)
(s/def ::system_id string?)
(s/def ::type part-types)
(s/def ::label string?)
(s/def ::description (s/nilable string?))
(s/def ::position_x int?)
(s/def ::position_y int?)
(s/def ::width int?)
(s/def ::height int?)
(s/def ::body_location (s/nilable string?))

(s/def ::node (s/keys :req-un [::system_id ::type ::label ::position_x ::position_y]
                      :opt-un [::id ::description ::width ::height ::body_location]))

(defn create-node!
  "Create a new node in a system"
  [data]
  (validate-spec ::node data)
  (db/insert! :nodes data))

(defn get-node
  "Get a node by ID"
  [id]
  (if-let [node (db/query-one
                 (db/sql-format
                  {:select [:*]
                   :from [:nodes]
                   :where [:= :id id]}))]
    node
    (throw (ex-info "Node not found" {:type :not-found :id id}))))

(defn update-node!
  "Update a node"
  [id data]
  (validate-spec ::node (assoc data :id id))
  (let [updated (db/update! :nodes data [:= :id id])]
    (if (seq updated)
      (first updated)
      (throw (ex-info "Node not found" {:type :not-found :id id})))))

(defn delete-node!
  "Delete a node. Returns a map with :id and :deleted keys, where :deleted is
  true if the node was found and deleted."
  [id]
  (let [result (db/delete! :nodes [:= :id id])
        count (or (:next.jdbc/update-count (first result)) 0)]
    {:id id :deleted (pos? count)}))
