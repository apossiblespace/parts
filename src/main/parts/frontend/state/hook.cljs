(ns parts.frontend.state.hook
  (:require
   [parts.frontend.state.system :as state]
   [parts.frontend.api.queue :as queue]
   [uix.core :refer [use-callback use-state]]))

(defn- find-new-id
  "Find the new ID that appears in updated-map but not in original-map"
  [original-map updated-map]
  (->> (keys updated-map)
       (filter #(not (contains? original-map %)))
       first))

(defn use-system-state
  "Custom hook for managing system state and related operations"
  [initial-data]
  (let [[state set-state] (use-state #(state/create-system-state initial-data))

        ;; Transform state to ReactFlow format
        nodes (state/get-reactflow-nodes state)
        edges (state/get-reactflow-edges state)

        ;; Part operations
        add-part (use-callback
                  (fn [part-attrs]
                    (let [prev-parts (:parts state)
                          updated-state (state/add-part state part-attrs)
                          new-part-id (find-new-id prev-parts (:parts updated-state))
                          new-part (get-in updated-state [:parts new-part-id])]
                      (set-state updated-state)
                      (queue/add-events! :node [(state/prepare-part-create-event new-part)])
                      new-part-id))
                  [state])

        update-part (use-callback
                     (fn [part-id attrs]
                       (let [updated-state (state/update-part state part-id attrs)]
                         (set-state updated-state)
                         (queue/add-events! :node [(state/prepare-part-update-event part-id attrs)])))
                     [state])

        remove-part (use-callback
                     (fn [part-id]
                       (let [updated-state (state/remove-part state part-id)]
                         (set-state updated-state)
                         (queue/add-events! :node [(state/prepare-part-remove-event part-id)])))
                     [state])

        update-part-position (use-callback
                              (fn [part-id position dragging]
                                (let [updated-state (state/update-part-position state part-id position)]
                                  (set-state updated-state)
                                  (when-not dragging
                                    (queue/add-events! :node [(state/prepare-part-position-event
                                                               part-id position)]))))
                              [state])

        ;; Relationship operations
        add-relationship (use-callback
                          (fn [rel-attrs]
                            (let [prev-rels (:relationships state)
                                  updated-state (state/add-relationship state rel-attrs)
                                  new-rel-id (find-new-id prev-rels (:relationships updated-state))
                                  new-rel (get-in updated-state [:relationships new-rel-id])]
                              (set-state updated-state)
                              (queue/add-events! :edge [(state/prepare-relationship-create-event new-rel)])))
                          [state])

        update-relationship (use-callback
                             (fn [rel-id attrs]
                               (let [updated-state (state/update-relationship state rel-id attrs)]
                                 (set-state updated-state)
                                 (queue/add-events! :edge [(state/prepare-relationship-update-event rel-id attrs)])))
                             [state])

        remove-relationship (use-callback
                             (fn [rel-id]
                               (let [updated-state (state/remove-relationship state rel-id)]
                                 (set-state updated-state)
                                 (queue/add-events! :edge [(state/prepare-relationship-remove-event rel-id)])))
                             [state])]

    {:state state
     :nodes nodes
     :edges edges
     :add-part add-part
     :update-part update-part
     :remove-part remove-part
     :update-part-position update-part-position
     :add-relationship add-relationship
     :update-relationship update-relationship
     :remove-relationship remove-relationship}))
