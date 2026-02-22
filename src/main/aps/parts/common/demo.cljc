(ns aps.parts.common.demo
  "Shared demo system data for frontend playground and backend registration.")

(defn demo-part-attrs
  "Returns demo part attributes for a new system. Does not include :id.
   The caller is responsible for adding IDs (frontend generates UUIDs,
   backend lets the database assign them)."
  [system-id]
  [{:type       "firefighter"
    :label      "Escapist"
    :position_x 300
    :position_y 130
    :notes      "Numbs/distracts when the Exile gets activated. Could be substances, scrolling social media, shopping, etc."
    :system_id  system-id}
   {:type       "exile"
    :label      "Disappointed kid"
    :position_x 200
    :position_y 320
    :notes      "Carries shame/sadness from childhood events. Can get triggered by criticism/rejection."
    :system_id  system-id}
   {:type       "manager"
    :label      "Overachiever"
    :position_x 100
    :position_y 150
    :notes      "Shows up as workaholism, imposter syndrome, etc."
    :system_id  system-id}])

(defn demo-relationship-attrs
  "Returns demo relationship attributes. Takes a vector of created parts
   (with IDs) to extract the source/target references.
   
   Expected parts order: [firefighter, exile, manager]
   Creates:
   - firefighter -> exile (unknown)
   - manager -> exile (protective)"
  [parts]
  (let [firefighter (first (filter #(= "firefighter" (:type %)) parts))
        exile       (first (filter #(= "exile" (:type %)) parts))
        manager     (first (filter #(= "manager" (:type %)) parts))
        system-id   (:system_id (first parts))]
    [{:type      "unknown"
      :source_id (:id firefighter)
      :target_id (:id exile)
      :system_id system-id}
     {:type      "protective"
      :source_id (:id manager)
      :target_id (:id exile)
      :notes     "Overachieving behaviour protects exile from getting triggered."
      :system_id system-id}]))
