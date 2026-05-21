(ns aps.parts.frontend.state.map-updates
  "Pure state transitions for Map-metadata updates (rename).

   These are the re-frame handlers for `:map/rename`, `:map/update-success`
   and `:map/update-failure` with the re-frame registration peeled off.
   They live here, dependency-free, so the kaocha cljs suite can unit-test
   them — that suite carries only the base `deps.edn` classpath, with no
   re-frame. `state/handlers` requires this namespace and registers them.

   A Map's title is metadata in the bitemporal `map_metadata` table
   (ADR-0002); a rename goes through PUT /maps/:id — a separate path from
   the change-event batch that carries Parts and Relationships.")

(defn rename-map
  "Effects map for an optimistic Map rename: write `new-title` to `:map`
   immediately, stash the previous title under `[:maps :rename-rollback]`
   for rollback, and persist via the `:storage/update-map` effect."
  [db new-title]
  {:db                 (-> db
                           (assoc-in [:map :title] new-title)
                           (assoc-in [:maps :rename-rollback]
                                     (get-in db [:map :title])))
   :storage/update-map {:id       (get-in db [:map :id])
                        :map-data {:title new-title}}})

(defn apply-map-update
  "Land a successful map-metadata update. `entity/map/update!` returns
   metadata only — no Parts/Relationships (ADR-0002) — so this MERGES the
   response into `:map` instead of replacing it, which would blank the
   loaded canvas. Also syncs the matching Maps list entry and clears the
   rollback."
  [db updated-map]
  (-> db
      (update-in [:map] merge updated-map)
      (update :maps dissoc :rename-rollback)
      (update-in [:maps :list]
                 (fn [maps]
                   (when maps
                     (mapv (fn [m]
                             (if (= (:id m) (:id updated-map))
                               (merge m updated-map)
                               m))
                           maps))))))

(defn revert-map-update
  "Land a failed map-metadata update: roll the optimistic title back to the
   stashed previous value and surface the error."
  [db error]
  (let [previous (get-in db [:maps :rename-rollback])]
    (-> db
        (cond-> previous (assoc-in [:map :title] previous))
        (update :maps dissoc :rename-rollback)
        (assoc-in [:maps :error] error))))
