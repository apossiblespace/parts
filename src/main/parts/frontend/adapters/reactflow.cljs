(ns parts.frontend.adapters.reactflow
  (:require
   [parts.common.models.part :refer [make-part]]
   [parts.common.models.relationship :refer [make-relationship]]))

(defn part->node
  "Convert a Part to a ReactFlow node"
  [{:keys [id type label position_x position_y]}]
  #js {:id id
       :position #js {:x position_x :y position_y}
       :data #js {:label label
                  :type (name type)}})

(defn parts->nodes
  "Convert a sequence of Parts to an Array of ReactFlow nodes"
  [parts]
  (println "[parts->nodes]" parts)
  (to-array (map part->node parts)))

(defn node->part
  "Convert ReactFlow node to a Part"
  [node system-id]
  (let [node (js->clj node :keywordize-keys true)]
    (make-part {:id (:id node)
                :system_id system-id
                :type (get-in node [:data :type])
                :label (get-in node [:data :label])
                :position_x (get-in node [:position :x])
                :position_y (get-in node [:position :y])})))

(defn relationship->edge
  "Convert a Relationship to a ReactFlow edge"
  [{:keys [id source_id target_id type]}]
  #js {:id id
       :source source_id
       :target target_id
       :data #js {:relationship (name type)}
       :className (str "edge-" (name type))})

(defn relationships->edges
  "Convert a sequence of Relationships to an Array of ReactFlow edges"
  [relationships]
  (println "[relationships->edges]" relationships)
  (to-array (map relationship->edge relationships)))

(defn edge->relationship
  "Convert ReactFlow edge to a Relationship"
  [edge system-id]
  (let [edge (js->clj edge :keywordize-keys true)]
    (make-relationship {:id (:id edge)
                        :system_id system-id
                        :source_id (:source edge)
                        :target_id (:target edge)
                        :type (get-in edge [:data :relationship])})))
