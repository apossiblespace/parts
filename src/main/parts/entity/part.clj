(ns parts.entity.part
  "A part is one of the components of a system map (see parts.entity.system)"
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

(s/def ::part (s/keys :req-un [::system_id ::type ::label ::position_x ::position_y]
                      :opt-un [::id ::description ::width ::height ::body_location]))

(defn create-part!
  "Create a new part in a system"
  [data]
  (validate-spec ::part data)
  (db/insert! :parts data))

(defn get-part
  "Get a part by ID"
  [id]
  (if-let [part (db/query-one
                 (db/sql-format
                  {:select [:*]
                   :from [:parts]
                   :where [:= :id id]}))]
    part
    (throw (ex-info "Part not found" {:type :not-found :id id}))))

(defn update-part!
  "Update a part"
  [id data]
  (validate-spec ::part (assoc data :id id))
  (let [updated (db/update! :parts data [:= :id id])]
    (if (seq updated)
      (first updated)
      (throw (ex-info "Part not found" {:type :not-found :id id})))))

(defn delete-part!
  "Delete a part. Returns a map with :id and :deleted keys, where :deleted is
  true if the part was found and deleted."
  [id]
  (let [result (db/delete! :parts [:= :id id])
        count (or (:next.jdbc/update-count (first result)) 0)]
    {:id id :deleted (pos? count)}))
