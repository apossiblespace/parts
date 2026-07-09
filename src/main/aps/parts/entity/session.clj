(ns aps.parts.entity.session
  "Sessions: named markers on a Map's bitemporal timeline (ADR-0014).

   Membership is *derived*: content belongs to the Session whose
   `[anchor, next-anchor)` range holds its earliest valid-time — computed
   here from `bt/first-appearances`, never stamped on parts/relationships.
   Writes are out-of-band single-row operations (not change-events), each
   writing its own audit_log row — the audit trigger only fires on
   temporal tables."
  (:require
   [aps.parts.common.models.session :as model]
   [aps.parts.db :as db]
   [aps.parts.db.audit :as audit]
   [aps.parts.db.bitemporal :as bt]
   [clojure.spec.alpha :as s]
   [next.jdbc :as jdbc]))

(defn- snapshot
  "A Session row as jsonb-friendly audit data."
  [row]
  {:map_id          (str (:map_id row))
   :ordinal         (:ordinal row)
   :trigger         (:trigger row)
   :anchor_valid_at (str (:anchor_valid_at row))})

(defn fetch
  "The Session, scoped to its Map — a Session in another Map is not-found."
  [session-id map-id]
  (if-let [row (db/query-one
                (db/sql-format
                 {:select [:*]
                  :from   [:sessions]
                  :where  [:and
                           [:= :id (db/->uuid session-id)]
                           [:= :map_id (db/->uuid map-id)]]}))]
    row
    (throw (ex-info "Session not found" {:type :not-found :id session-id}))))

(defn index
  "A Map's Sessions ordered by anchor, each carrying its activated Part id
   (nil when none) — the shape the Session picker renders."
  [map-id]
  (db/query
   (db/sql-format
    {:select    [:s.* [:sa.part_id :activated_part_id]]
     :from      [[:sessions :s]]
     :left-join [[:session_activations :sa] [:= :sa.session_id :s.id]]
     :where     [:= :s.map_id (db/->uuid map-id)]
     :order-by  [[:s.anchor_valid_at :asc]]})))

(defn- latest
  "The active Session — the latest by anchor — or nil for a Map with none."
  [map-id]
  (db/query-one
   (db/sql-format
    {:select   [:*]
     :from     [:sessions]
     :where    [:= :map_id (db/->uuid map-id)]
     :order-by [[:anchor_valid_at :desc]]
     :limit    1})))

(defn create!
  "Open a new Session: the anchor is captured server-side at creation, the
   ordinal is the Map's next. Creating a Session implicitly closes the
   previous one — its range now ends at this anchor. Always a separate
   write from any content batch, so the anchor precedes content attributed
   to the new Session (ADR-0014's ordering guard)."
  [map-id actor-id]
  (jdbc/with-transaction [tx db/datasource]
    (let [map-uuid (db/->uuid map-id)
          next-ord (-> (jdbc/execute-one!
                        tx
                        (db/sql-format
                         {:select [[[:+ [:coalesce [:max :ordinal] 0] 1] :next]]
                          :from   [:sessions]
                          :where  [:= :map_id map-uuid]}))
                       :next)
          row      (db/insert! :sessions
                               {:map_id          map-uuid
                                :ordinal         next-ord
                                :trigger         nil
                                :anchor_valid_at [:now]}
                               tx)]
      (audit/record! tx {:actor-id actor-id
                         :table    :sessions
                         :op       :insert
                         :row-id   (:id row)
                         :after    (snapshot row)})
      row)))

;; -- Derived membership -----------------------------------------------------

(defn- covering
  "The Session (from `sessions`, ordered by anchor) whose
   `[anchor, next-anchor)` range holds instant `t` — inclusive lower bound;
   nil when `t` precedes the first anchor."
  [sessions t]
  (let [t (db/->instant t)]
    (->> sessions
         (take-while #(not (pos? (compare (db/->instant (:anchor_valid_at %)) t))))
         last)))

(defn session-at
  "The Session covering `valid-t` on this Map's timeline — the same
   bucketing the badge and the Session-aware PDF read from."
  [map-id valid-t]
  (covering (index map-id) valid-t))

(defn first-appearances
  "Which Session did each Part and Relationship first appear in? Returns
   `{entity-id → session-row}` for every entity ever recorded on the Map
   (retracted included — see `bt/first-appearances`), bucketed into anchor
   ranges; there is no session_id column anywhere (ADR-0014)."
  [map-id]
  (let [sessions (index map-id)
        scope    [:= :map_id (db/->uuid map-id)]
        firsts   (merge (bt/first-appearances db/datasource :parts scope)
                        (bt/first-appearances db/datasource :relationships scope))]
    (into {}
          (keep (fn [[id first-at]]
                  (when-let [s (covering sessions first-at)]
                    [id s])))
          firsts)))

;; -- Narrow mutation (ADR-0014, "Session mutation") ---------------------------

(defn- require-latest!
  "The mutation rules only ever apply to the active (latest) Session."
  [session-id map-id]
  (let [session (fetch session-id map-id)
        active  (latest map-id)]
    (when (not= (:id session) (:id active))
      (throw (ex-info "Only the active (latest) Session can be modified"
                      {:type :validation :id session-id})))
    session))

(defn update-trigger!
  "Set the active Session's trigger text. A past Session's trigger is
   locked along with its content — the past is read-only."
  [session-id map-id trigger actor-id]
  (let [before (require-latest! session-id map-id)]
    (when-not (s/valid? ::model/trigger trigger)
      (throw (ex-info "Trigger must be text" {:type :validation})))
    (jdbc/with-transaction [tx db/datasource]
      (let [after (first (db/update! :sessions
                                     {:trigger trigger}
                                     [:= :id (db/->uuid session-id)]
                                     tx))]
        (audit/record! tx {:actor-id actor-id
                           :table    :sessions
                           :op       :update
                           :row-id   (:id after)
                           :before   (snapshot before)
                           :after    (snapshot after)})
        after))))

(defn- current-activation
  "The Part id this Session activated, or nil."
  [session-id]
  (:part_id (db/query-one
             (db/sql-format
              {:select [:part_id]
               :from   [:session_activations]
               :where  [:= :session_id session-id]}))))

(defn- require-empty!
  "Deletion is the started-by-mistake undo: allowed only when nothing first
   appeared in the Session's range and nothing is activated. Content edits
   to older entities don't count — membership derives from first
   appearance, so re-homing the range shifts nothing."
  [session map-id]
  (when (some #(= (:id session) (:id %)) (vals (first-appearances map-id)))
    (throw (ex-info "Session has content and cannot be deleted"
                    {:type :validation :id (:id session)})))
  (when (current-activation (:id session))
    (throw (ex-info "Session has an activation and cannot be deleted"
                    {:type :validation :id (:id session)}))))

(defn delete!
  "Delete the latest Session when it is empty — re-activating the previous
   Session. Anything else would silently re-home content and shift every
   downstream badge (ADR-0014)."
  [session-id map-id actor-id]
  (let [session (require-latest! session-id map-id)]
    (require-empty! session map-id)
    (jdbc/with-transaction [tx db/datasource]
      (db/delete! :sessions [:= :id (:id session)] tx)
      (audit/record! tx {:actor-id actor-id
                         :table    :sessions
                         :op       :delete
                         :row-id   (:id session)
                         :before   (snapshot session)})
      {:id (:id session) :deleted true})))

;; -- Activation link ----------------------------------------------------------

(defn- validate-part!
  "The activated Part must currently exist in the same Map — mirrors the
   endpoint rule for Relationships."
  [map-id part-id]
  (let [rows (bt/live-rows db/datasource :parts
                           [:and
                            [:= :map_id (db/->uuid map-id)]
                            [:= :id (db/->uuid part-id)]])]
    (when (empty? rows)
      (throw (ex-info "Activation target is not a Part in this Map"
                      {:type :validation :part-id part-id :map-id map-id})))))

(defn- activation-snapshot [session-id part-id]
  {:session_id (str session-id) :part_id (str part-id)})

(defn set-activation!
  "Link the one Part this Session activated (ADR-0014's launch shape B).
   Setting replaces any existing link — at most one per Session at launch,
   enforced here rather than in the schema. A replaced link is audited as
   an update carrying the previous Part, so the unlink is never invisible."
  [session-id map-id part-id actor-id]
  (let [session   (fetch session-id map-id)
        part-uuid (db/->uuid part-id)
        previous  (current-activation (:id session))]
    (validate-part! map-id part-id)
    (jdbc/with-transaction [tx db/datasource]
      (db/delete! :session_activations [:= :session_id (:id session)] tx)
      (db/insert! :session_activations
                  {:session_id (:id session)
                   :part_id    part-uuid}
                  tx)
      (audit/record! tx {:actor-id actor-id
                         :table    :session_activations
                         :op       (if previous :update :insert)
                         :row-id   (:id session)
                         :before   (when previous
                                     (activation-snapshot (:id session) previous))
                         :after    (activation-snapshot (:id session) part-uuid)}))
    (assoc session :activated_part_id part-uuid)))

(defn clear-activation!
  "Remove the Session's activated-Part link. A no-op (and no audit noise)
   when nothing was linked."
  [session-id map-id actor-id]
  (let [session  (fetch session-id map-id)
        previous (current-activation (:id session))]
    (when previous
      (jdbc/with-transaction [tx db/datasource]
        (db/delete! :session_activations [:= :session_id (:id session)] tx)
        (audit/record! tx {:actor-id actor-id
                           :table    :session_activations
                           :op       :delete
                           :row-id   (:id session)
                           :before   (activation-snapshot (:id session) previous)})))
    {:id (:id session) :cleared (some? previous)}))
