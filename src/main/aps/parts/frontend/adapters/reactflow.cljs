(ns aps.parts.frontend.adapters.reactflow
  (:require
   [aps.parts.common.geometry :as geometry]
   [aps.parts.common.models.part :refer [make-part]]
   [aps.parts.common.models.relationship :refer [make-relationship]]
   [aps.parts.common.observe :as o]))

(defn part->node
  "Convert a Part to a ReactFlow node"
  ([part] (part->node part nil))
  ([{:keys [id type label notes position_x position_y width height]} selected-ids]
   #js {:id       id
        :position #js {:x position_x :y position_y}
        :selected (when selected-ids (contains? selected-ids id))
        :width    (or width 100)
        :height   (or height 100)
        :data     #js {:label label
                       :type  (name type)
                       :notes notes}}))

(defn parts->nodes
  "Convert a sequence of Parts to an Array of ReactFlow nodes.
  The optional selected-ids param is a vector containing the IDs of Parts the
  nodes for which should be marked as selected."
  ([parts] (parts->nodes parts nil))
  ([parts selected-ids]
   (o/debug
    "reactflow.parts->nodes"
    "converting parts to nodes" (count parts)
    "selected:" selected-ids)
   (let [selected-id-set (when selected-ids (set selected-ids))]
     (to-array (map #(part->node % selected-id-set) parts)))))

(defn node->part
  "Convert ReactFlow node to a Part"
  [node map-id]
  (let [node (js->clj node :keywordize-keys true)]
    (make-part {:id         (:id node)
                :map_id     map-id
                :type       (get-in node [:data :type])
                :label      (get-in node [:data :label])
                :notes      (get-in node [:data :notes])
                :position_x (get-in node [:position :x])
                :position_y (get-in node [:position :y])})))

;; Handle ids used by the easy-connect pattern. Both nodes.cljs (Handle :id)
;; and the edge construction below must agree — if these drift, ReactFlow's
;; connectionLookup collides keys for bidirectional pairs and drag-select
;; silently misses one edge of every pair.
(def source-handle-id "src")
(def target-handle-id "tgt")

(defn relationship->edge
  "Convert a Relationship to a ReactFlow edge.

   sourceHandle/targetHandle are pinned to constants so ReactFlow's
   internal connectionLookup gives A->B and B->A distinct keys; without
   that, drag-select misses one edge of every bidirectional pair (see
   updateConnectionLookup in @xyflow/system).

   `bidir?` is precomputed by `relationships->edges` and threaded into
   `:data` so the edge component can pick its curve shape without doing
   its own O(N) reverse-sibling scan per render."
  ([relationship] (relationship->edge relationship nil false))
  ([{:keys [id source_id target_id notes type]} selected-id-set bidir?]
   (let [type-name (name type)]
     #js {:id           id
          :source       source_id
          :sourceHandle source-handle-id
          :target       target_id
          :targetHandle target-handle-id
          :selected     (when selected-id-set (contains? selected-id-set id))
          :data         #js {:relationship type-name
                             :notes        notes
                             :bidir        bidir?}
          :className    (str "edge-" type-name)
          ;; ReactFlow's EdgeWrapper wraps `:markerEnd` in `url('#...')`
          ;; itself, so we pass just the bare marker id. Passing the
          ;; already-wrapped form yields `url('#url(#edge-arrow)')`.
          :markerEnd    "edge-arrow"})))

(defn relationships->edges
  "Convert a sequence of Relationships to an Array of ReactFlow edges.
  The optional selected-ids param is a vector containing the IDs of selected
  Relationships, the edges for which should be marked as selected."
  ([relationships] (relationships->edges relationships nil))
  ([relationships selected-ids]
   (o/debug
    "reactflow.relationships->edges"
    "converting relationships to edges"
    (count relationships))
   (let [selected-id-set (when selected-ids (set selected-ids))
         bidi-pairs      (geometry/bidirectional-pairs relationships)]
     (to-array (map #(relationship->edge % selected-id-set
                                         (geometry/bidirectional? bidi-pairs %))
                    relationships)))))

(defn edge->relationship
  "Convert ReactFlow edge to a Relationship"
  [edge map-id]
  (let [edge (js->clj edge :keywordize-keys true)]
    (make-relationship {:id        (:id edge)
                        :map_id    map-id
                        :source_id (:source edge)
                        :target_id (:target edge)
                        :type      (get-in edge [:data :relationship])
                        :notes     (get-in edge [:data :notes])})))

;; -- ReactFlow event translation ------------------------------------------
;; ReactFlow's change-event vocabulary lives here. Consumers receive
;; domain-level intents and never `case`-match on "position" / "select" / "remove".

(defn- translate-node-change [change]
  (case (:type change)
    "position" (when-let [position (:position change)]
                 (let [frame {:intent   :part-position-frame
                              :id       (:id change)
                              :position position}]
                   (if (:dragging change)
                     [frame]
                     [frame {:intent   :part-moved
                             :id       (:id change)
                             :position position}])))
    "select"   [{:intent    :part-selected
                 :id        (:id change)
                 :selected? (:selected change)}]
    "remove"   [{:intent :part-removed :id (:id change)}]
    (do (o/warn "reactflow.translate-node-change" "unhandled change type" change)
        nil)))

(defn translate-nodes-change
  "Translate a ReactFlow `onNodesChange` JS payload into a vector of domain
   intents. A position change always yields `:part-position-frame`; when not
   dragging, it additionally yields `:part-moved`."
  [js-changes]
  (->> (js->clj js-changes :keywordize-keys true)
       (mapcat translate-node-change)
       vec))

(defn- translate-edge-change [change]
  (case (:type change)
    "select" [{:intent    :relationship-selected
               :id        (:id change)
               :selected? (:selected change)}]
    "remove" [{:intent :relationship-removed :id (:id change)}]
    (do (o/warn "reactflow.translate-edge-change" "unhandled change type" change)
        nil)))

(defn translate-edges-change
  "Translate a ReactFlow `onEdgesChange` JS payload into a vector of domain
   intents."
  [js-changes]
  (->> (js->clj js-changes :keywordize-keys true)
       (mapcat translate-edge-change)
       vec))

(defn translate-connect
  "Translate a ReactFlow `onConnect` JS connection payload into a
   `:relationship-connected` intent."
  [js-connection]
  (let [conn (js->clj js-connection :keywordize-keys true)]
    {:intent    :relationship-connected
     :source_id (:source conn)
     :target_id (:target conn)}))
