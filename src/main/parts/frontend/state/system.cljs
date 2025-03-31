(ns parts.frontend.state.system
  (:require
   [parts.common.models.part :as part]
   [parts.common.models.relationship :as relationship]
   [parts.frontend.adapters.reactflow :as adapter]
   [clojure.spec.alpha :as s]))

;; Normalized state structure
(defn create-system-state
  "Creates initial system state from parts and relationships"
  [{:keys [id parts relationships]}]
  {:system-id id
   :parts (reduce #(assoc %1 (:id %2) %2) {} parts)
   :relationships (reduce #(assoc %1 (:id %2) %2) {} relationships)})

;; -- Part operations --

(defn add-part
  "Adds a new part to the system state. Returns updated state."
  [state part-attrs]
  (let [part (part/make-part (assoc part-attrs :system_id (:system-id state)))
        id (:id part)]
    (assoc-in state [:parts id] part)))

(defn get-part
  "Gets a part by id, or nil if not found"
  [state part-id]
  (get-in state [:parts part-id]))

(defn update-part
  "Updates an existing part with new attributes. Validates resulting part.
   Returns updated state, or throws if validation fails."
  [state part-id attrs]
  (if-let [existing (get-part state part-id)]
    (let [updated (merge existing attrs)
          valid? (s/valid? :parts.common.models.part/part updated)]
      (if valid?
        (assoc-in state [:parts part-id] updated)
        (throw (ex-info "Invalid part data after update"
                        {:type ::invalid-part
                         :spec-error (s/explain-data :parts.common.models.part/part updated)
                         :data updated}))))
    state))

(defn update-part-position
  "Updates a part's position from ReactFlow position change event"
  [state part-id position]
  (update-part state part-id
               {:position_x (int (:x position))
                :position_y (int (:y position))}))

(defn remove-part
  "Removes a part from the system and any relationships connected to it"
  [state part-id]
  (if (contains? (:parts state) part-id)
    (let [related-rel-ids (->> (:relationships state)
                               (filter (fn [[_ rel]]
                                         (or (= part-id (:source_id rel))
                                             (= part-id (:target_id rel)))))
                               (map first))]
      (-> state
          (update :parts dissoc part-id)
          (update :relationships #(apply dissoc % related-rel-ids))))
    state))

;; -- Relationship operations --

(defn add-relationship
  "Adds a new relationship to the system state. Returns updated state."
  [state rel-attrs]
  (let [rel (relationship/make-relationship (assoc rel-attrs :system_id (:system-id state)))
        id (:id rel)]
    (assoc-in state [:relationships id] rel)))

(defn get-relationship
  "Gets a relationship by id, or nil if not found"
  [state rel-id]
  (get-in state [:relationships rel-id]))

(defn update-relationship
  "Updates an existing relationship with new attributes. Validates resulting relationship.
   Returns updated state, or throws if validation fails."
  [state rel-id attrs]
  (if-let [existing (get-relationship state rel-id)]
    (let [updated (merge existing attrs)
          valid? (s/valid? :parts.common.models.relationship/relationship updated)]
      (if valid?
        (assoc-in state [:relationships rel-id] updated)
        (throw (ex-info "Invalid relationship data after update"
                        {:type ::invalid-relationship
                         :spec-error (s/explain-data :parts.common.models.relationship/relationship updated)
                         :data updated}))))
    state))

(defn remove-relationship
  "Removes a relationship"
  [state rel-id]
  (update state :relationships dissoc rel-id))

;; -- ReactFlow data preparation --

(defn get-reactflow-nodes
  "Converts the current state parts to ReactFlow nodes"
  [state]
  (adapter/parts->nodes (vals (:parts state))))

(defn get-reactflow-edges
  "Converts the current state relationships to ReactFlow edges"
  [state]
  (adapter/relationships->edges (vals (:relationships state))))

;; -- Event data preparation --

(defn prepare-part-create-event
  "Prepares event data for part creation"
  [part]
  {:id (:id part)
   :type "create"
   :data (select-keys part [:type :label])})

(defn prepare-part-update-event
  "Prepares event data for part update"
  [part-id attrs]
  {:id part-id
   :type "update"
   :data attrs})

(defn prepare-part-remove-event
  "Prepares event data for part removal"
  [part-id]
  {:id part-id
   :type "remove"
   :data {}})

(defn prepare-part-position-event
  "Prepares event data for part position update"
  [part-id position]
  {:id part-id
   :type "position"
   :position position
   :dragging false})

(defn prepare-relationship-create-event
  "Prepares event data for relationship creation"
  [rel]
  {:id (:id rel)
   :type "create"
   :data (select-keys rel [:type :source_id :target_id])})

(defn prepare-relationship-update-event
  "Prepares event data for relationship update"
  [rel-id attrs]
  {:id rel-id
   :type "update"
   :data attrs})

(defn prepare-relationship-remove-event
  "Prepares event data for relationship removal"
  [rel-id]
  {:id rel-id
   :type "remove"
   :data {}})
