(ns aps.parts.export
  "Builds a Map's data-subject export — the machine-readable copy of a Map's
   full valid-time history, for GDPR Art. 15 / 20 and the ToS 'export your Maps
   at any time' promise (ADR-0010).

   Composes `db.bitemporal/history`; it holds no temporal SQL of its own, so the
   temporal vocabulary stays quarantined behind that reader's
   `valid_from` / `valid_to` surface."
  (:require
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [aps.parts.entity.session :as session]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import
   (java.time OffsetDateTime)))

(def format-version
  "Bumped when the export shape changes incompatibly — lets a future importer
   detect the format (ADR-0010)."
  "1")

(def ^:private exported-columns
  "Per-entity allowlists of exported keys (ADR-0010 data minimization). A
   column added to one of these tables is NOT exported until deliberately
   named here — so controller metadata and any future internal/sensitive
   field stay out by default, rather than leaking on the day the column
   lands. The export's REQUIRED clinical fields (notes, body_location,
   trigger) are all present."
  {:parts         [:type :label :description :notes :position_x :position_y
                   :width :height :body_location :valid_from :valid_to]
   :relationships [:type :source_id :target_id :notes :intensity
                   :valid_from :valid_to]
   :map-metadata  [:title :valid_from :valid_to]
   :sessions      [:id :ordinal :trigger :anchor_valid_at :activated_part_id]})

(defn- project
  [entity row]
  (select-keys row (exported-columns entity)))

(defn- by-entity
  "Group history versions by entity id into `[{:id … :versions […]}]`, earliest
   entity first. Each version is projected through `exported-columns`."
  [entity versions]
  (->> versions
       (group-by :id)
       (map (fn [[id vs]]
              {:id id :versions (mapv #(project entity %) vs)}))
       (sort-by (comp :valid_from first :versions))
       vec))

(defn- map-identity
  "The Map's non-temporal identity for the export: `id` + `created_at` only.
   `owner_id` / `actor_id` / `deleted_at` are controller metadata, excluded per
   ADR-0010."
  [ds map-id]
  (jdbc/execute-one! ds
                     ["SELECT id, created_at FROM maps WHERE id = ?::uuid" (str map-id)]
                     {:builder-fn rs/as-unqualified-maps}))

(defn export-map
  "The full export for one Map as a plain data structure (ADR-0010 shape).
   Owner-scoping is the caller's responsibility (`wrap-map-access`); this assumes
   the Map exists and is the subject's to export."
  [ds map-id]
  (let [mid (db/->uuid map-id)]
    {:format_version format-version
     :exported_at    (OffsetDateTime/now)
     :map            (assoc (map-identity ds mid)
                            :title_history
                            (mapv #(project :map-metadata %)
                                  (bt/history ds :map_metadata [:= :map_id mid])))
     :parts          (by-entity :parts (bt/history ds :parts [:= :map_id mid]))
     :relationships  (by-entity :relationships
                                (bt/history ds :relationships [:= :map_id mid]))
     ;; Sessions are non-temporal (ADR-0014): each exports once,
     ;; unversioned — the anchor instant is its valid-time place.
     :sessions       (mapv #(project :sessions %) (session/index ds mid))}))
