(ns aps.parts.frontend.storage.ids
  "Id normalization for Maps fetched from the API. Dependency-free so the
   kaocha cljs suite (no cljs-http on its classpath) can test it.")

(defn normalize-map-ids
  "Converts all UUID objects in a map's data to strings.
   Transit decodes Java UUIDs as CLJS UUID objects, but the rest of the app
   (ReactFlow adapters, localStorage backend) expects plain strings — a
   UUID object as a ReactFlow id breaks its nodeLookup (JS Maps compare
   object keys by reference), which hides every edge. Shared by the
   HTTP backend's Map load and the Time-travel snapshot fetch, which must
   normalize identically."
  [the-map]
  (let [each (fn [ks] #(mapv (fn [m] (reduce (fn [m k] (update m k str)) m ks)) %))]
    (-> the-map
        (update :id str)
        (update :parts (each [:id :map_id]))
        (update :relationships (each [:id :map_id :source_id :target_id]))
        (update :conversation_entries (each [:id :map_id :part_id])))))
