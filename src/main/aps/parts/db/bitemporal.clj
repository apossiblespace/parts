(ns aps.parts.db.bitemporal
  "Bitemporal data layer for entity-snapshot tables (`parts`, `relationships`,
   `map_metadata`).

   Every row is a complete snapshot of an entity, valid for some
   `valid_at` interval, recorded for some `sys_period` interval.

   Only this namespace and `aps.parts.db.range-types` may emit temporal SQL.
   Other code calls the functions here.

   Vocabulary
   ----------

   Ref. Snodgrass, ch 6-10: https://www2.cs.arizona.edu/~rts/tdbbook.pdf

   Every bitemporal row sits on two time axes:

   - VT (valid time):       when the fact was true in the world.
                            Stored as `valid_at tstzrange`.
   - TT (transaction time): when we recorded the fact in the database.
                            Stored as `sys_period tstzrange`.

   Each temporal operation picks one of three treatments per axis:

   - current:      applies at `now()` only.
   - sequenced:    respects temporal semantics; ranges over a span of time,
                   or a single chosen point within it (time-slice).
   - nonsequenced: treats the range column as ordinary data; no temporal
                   semantics applied.

   Sequenced writes
   ----------------

   `insert!` / `update!` / `correction!` / `retract!` operate over valid-time
   spans, not single instants. `update!` runs the canonical split-retract-
   insert pattern (Ch 7) so past valid-times stay preserved while the new
   value covers `[now, infinity)`. The no-overlap invariant on both axes is
   enforced by the EXCLUDE constraint in the schema.

   Reads
   -----
   We implement 3 of the 9 variants in the query matrix (Ch 10)

   The matrix crosses three VT treatments × three TT treatments:

   ┌─────────────────┬──────────────┬──────────────┬──────────────┐
   │                 │ TT-current   │ TT-sequenced │ TT-nonseq.   │
   ├─────────────────┼──────────────┼──────────────┼──────────────┤
   │ VT-current      │ as-of-now    │ -            │ -            │
   │ VT-sequenced    │ as-of-valid  │ as-known-on  │ -            │
   │ VT-nonsequenced │ -            │ -            │ -            │
   └─────────────────┴──────────────┴──────────────┴──────────────┘

   Reading the axes:

   - VT-current:      keep rows where `valid_at` contains `now()`
                      (i.e., what's true right now in the world).
   - VT-sequenced:    keep rows where `valid_at` contains a chosen point T
                      (time-slice); or fold across the full VT history.
   - VT-nonsequenced: `valid_at` is just a column with no temporal meaning.

   - TT-current:      keep rows where `sys_period` contains `now()`
                      (i.e., still believed; not superseded by a later
                      record).
   - TT-sequenced:    keep rows where `sys_period` contains a chosen point
                      S; or fold across the full TT history.
   - TT-nonsequenced: `sys_period` is just a column with no temporal meaning.

   The three we implement:

   - as-of-now:       the live view (true now, as best known now)
   - as-of-valid:     the scrubber (true at past T, as best known now)
   - as-known-on      audit / debugging (what we believed at S about T)

   The other six cells answer rare-in-practice questions:

   - cells with TT-sequenced (and not VT-time-slice): \"audit-of-audit\"
     views: how our beliefs about a fact changed over time.
   - cells with TT-nonsequenced: introspecting `sys_period` as raw data,
     mostly useful for migrations or one-off fix-up scripts.
   - cells with VT-nonsequenced: same, for `valid_at`.

   All six are implementable on this schema; see Snodgrass Ch 10 for examples."
  (:require
   [aps.parts.db.range-types :as range]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

;; -- HoneySQL operator registration ----------------------------------------
;;
;; HoneySQL doesn't recognize PG-specific range operators by default. Register
;; them at load time so `[:@> :valid_at [:now]]` compiles to `valid_at @> now()`
;; rather than the tuple form. We're the only consumer in the codebase today;
;; if another namespace adopts range queries, it can require us (or register
;; the operators itself).

(sql/register-op! (keyword "@>"))
(sql/register-op! (keyword "<@"))
(sql/register-op! (keyword "&&"))
(sql/register-op! (keyword "-|-"))

;; -- Helpers ---------------------------------------------------------------

(defn set-actor!
  "Override the audit trigger's actor attribution for the rest of this
   transaction.

   Only needed when the acting party differs from the row's `actor_id`
   column — e.g., during account erasure, where we want audit rows to
   attribute to the tombstone user instead of the user being deleted.

   For ordinary writes, this is unnecessary: the audit trigger reads from
   `NEW.actor_id` (a NOT NULL column on every temporal table) as a fallback,
   so audit attribution is correct without any session-level setup."
  [tx actor-id]
  (jdbc/execute! tx
                 ["SELECT set_config('aps.actor_id', ?, true)" (str actor-id)]))

(def ^:private tt-current
  "HoneySQL predicate: row is currently believed (transaction-time-current).
   `upper(sys_period) = 'infinity'`."
  [:= [:upper :sys_period] [:cast [:inline "infinity"] :timestamptz]])

(defn- vt-contains
  "HoneySQL predicate: `valid_at @> $t`."
  [t]
  [(keyword "@>") :valid_at [:cast t :timestamptz]])

(defn- tt-contains
  "HoneySQL predicate: `sys_period @> $t`."
  [t]
  [(keyword "@>") :sys_period [:cast t :timestamptz]])

(def ^:private exec-opts
  {:builder-fn rs/as-unqualified-maps})

(defn- internal-cols
  "Bitemporal bookkeeping columns. Stripped from rows returned to callers."
  [row]
  (dissoc row :valid_at :sys_period :actor_id))

(defn- with-tx
  "Run `f` in a transaction. If `tx` is already a Connection in an open
   transaction, calls `f` with it directly — opening a nested
   `jdbc/with-transaction` on a connection that's already in a transaction
   commits the parent's pending work prematurely (it's not a savepoint)."
  [tx f]
  (if (and (instance? java.sql.Connection tx)
           (not (.getAutoCommit ^java.sql.Connection tx)))
    (f tx)
    (jdbc/with-transaction [inner tx]
      (f inner))))

(defn- find-current-row [tx table id]
  (first
   (jdbc/execute! tx
                  (sql/format {:select [:*]
                               :from   [table]
                               :where  [:and
                                        [:= :id id]
                                        tt-current
                                        (vt-contains [:now])]})
                  exec-opts)))

(defn- close-current-row!
  "Advance the `sys_period` upper bound of the currently-in-effect row to
   `now()`. Matches by id + TT-current + VT-current — other open-sys_period
   rows (historical-belief slices from prior updates) keep their state."
  [tx table id]
  (jdbc/execute! tx
                 (sql/format {:update table
                              :set    {:sys_period [:tstzrange
                                                    [:lower :sys_period]
                                                    [:now]
                                                    [:inline "[)"]]}
                              :where  [:and
                                       [:= :id id]
                                       tt-current
                                       (vt-contains [:now])]})))

;; -- Writes ----------------------------------------------------------------
;;
;; Audit attribution is automatic: the trigger reads `NEW.actor_id` from
;; the row, which `insert!` populates from the `:actor-id` opt. The session
;; variable (`set-actor!`) is only needed to *override* the row column —
;; see its docstring.

(defn insert!
  "Insert a new bitemporal row. Returns the inserted row (without internal cols).

   `row` is a map of business-column → value. Must include `:id`.

   Opts:
     :actor-id  (required)  written to the row's `actor_id` column; the audit
                            trigger reads this for attribution
     :valid-at  (optional)  TstzRange (defaults to [now, infinity))"
  [tx table row {:keys [actor-id valid-at]}]
  (assert actor-id "actor-id is required")
  (assert (:id row) "row :id is required")
  (let [valid-at (or valid-at (range/now-to-infinity))
        full-row (assoc row
                        :valid_at   (range/->pgobject valid-at)
                        :sys_period (range/->pgobject (range/now-to-infinity))
                        :actor_id   actor-id)
        inserted (first (jdbc/execute! tx
                                       (sql/format {:insert-into table
                                                    :values      [full-row]
                                                    :returning   :*})
                                       exec-opts))]
    (internal-cols inserted)))

(defn update!
  "Sequenced update — the entity's values change starting *now*.

   Inside one transaction (canonical split-retract-insert; Snodgrass Ch 7):
     1. Find the currently-believed row where `id = ?`.
     2. Close its `sys_period` upper bound at `now()`.
     3. Insert a *historical* row preserving the previous values over
        `valid_at = [old-lower, now)` — so as-of-valid queries at past
        instants still return the entity (with the old values).
     4. Insert a *new* row with merged values over `valid_at = [now, infinity)`.

   Returns the new row (without internal cols).

   For asserting a fact about a non-overlapping past valid time, use
   `insert!` directly with an explicit `:valid-at`."
  [tx table id changes {:keys [actor-id]}]
  (assert actor-id "actor-id is required")
  (with-tx tx
    (fn [tx]
      (let [current (find-current-row tx table id)]
        (when-not current
          (throw (ex-info "Row not found in current state"
                          {:type :not-found :id id :table table})))
        (close-current-row! tx table id)
        (let [now-ts          (java.time.OffsetDateTime/now)
              old-valid       (:valid_at current)
              old-lower       (:lower old-valid)
              old-values      (internal-cols current)
              ;; {:id id} re-asserts the row key: a sequenced update changes
              ;; attributes, never identity — `changes` can't relocate the row.
              new-values      (merge old-values changes {:id id})
              past-non-empty? (or (= old-lower :-infinity)
                                  (and (instance? java.time.OffsetDateTime old-lower)
                                       (.isBefore ^java.time.OffsetDateTime old-lower now-ts)))]
          (when past-non-empty?
            (insert! tx table old-values
                     {:actor-id actor-id
                      :valid-at (range/tstzrange old-lower now-ts "[)")}))
          (insert! tx table new-values
                   {:actor-id actor-id
                    :valid-at (range/tstzrange now-ts :infinity "[)")}))))))

(defn correction!
  "Bitemporal correction — rewrite *belief* about the currently-in-effect row
   without changing its valid-time extent.

   Use case: 'I recorded this wrong last session — the part was always a
   Manager, not a Firefighter, for the same valid time we already have.'

   Contrast with `update!`, which says 'from now on the value changes'
   (introducing a new valid-time slice). `correction!` leaves the valid_at
   range untouched; only `sys_period` advances.

   Inside one transaction:
     1. Find the currently-in-effect row (id + TT-current + VT @> now).
     2. Close its `sys_period` upper at `now()`.
     3. Insert a corrected row with the *same* `valid_at` and the merged values."
  [tx table id changes {:keys [actor-id]}]
  (assert actor-id "actor-id is required")
  (with-tx tx
    (fn [tx]
      (let [current (find-current-row tx table id)]
        (when-not current
          (throw (ex-info "Row not found in current state"
                          {:type :not-found :id id :table table})))
        (close-current-row! tx table id)
        (let [old-valid  (:valid_at current)
              old-values (internal-cols current)
              new-values (merge old-values changes {:id id})]
          (insert! tx table new-values
                   {:actor-id actor-id
                    :valid-at old-valid}))))))

(defn retract!
  "Retract a bitemporal entity — the entity no longer exists from `now` on.

   Closes the currently-active row's `sys_period` and inserts a successor
   with `valid_at` upper-bounded at `now()`. After this, `as-of-now` queries
   no longer return the entity, but `as-of-valid` at any past instant still
   does.

   Returns `{:retracted true :id id}` or `{:retracted false :id id}` if no
   current row was found."
  [tx table id {:keys [actor-id]}]
  (assert actor-id "actor-id is required")
  (with-tx tx
    (fn [tx]
      (if-let [current (find-current-row tx table id)]
        (let [_            (close-current-row! tx table id)
              old-valid    (:valid_at current)
              successor    (internal-cols current)
              closed-valid (range/tstzrange (:lower old-valid)
                                            (java.time.OffsetDateTime/now)
                                            "[)")]
          (insert! tx table successor {:actor-id actor-id :valid-at closed-valid})
          {:retracted true :id id})
        {:retracted false :id id}))))

;; -- Reads -----------------------------------------------------------------

(defn- query
  ([ds table where]
   (query ds table where nil))
  ([ds table where extra-where]
   (->> (jdbc/execute! ds
                       (sql/format {:select [:*]
                                    :from   [table]
                                    :where  (if extra-where
                                              (into [:and extra-where] (when where [where]))
                                              where)})
                       exec-opts)
        (map internal-cols))))

(defn as-of-now
  "Time-slice: VT-current + TT-current. The default `WHERE`-less reader."
  ([ds table]
   (as-of-now ds table nil))
  ([ds table where]
   (query ds table where (into [:and tt-current (vt-contains [:now])]))))

(defn as-of-valid
  "Time-slice: VT-time-slice + TT-current. Powers the scrubber:
   'what did the map look like at `valid-t`, as best known?'"
  ([ds table valid-t]
   (as-of-valid ds table valid-t nil))
  ([ds table valid-t where]
   (query ds table where (into [:and tt-current (vt-contains valid-t)]))))

(defn as-known-on
  "Bitemporal time-slice: 'what did we believe on `sys-t` about `valid-t`?'
   Audit / debugging view."
  ([ds table sys-t valid-t]
   (as-known-on ds table sys-t valid-t nil))
  ([ds table sys-t valid-t where]
   (query ds table where (into [:and (tt-contains sys-t) (vt-contains valid-t)]))))

(defn latest-change-at
  "Most recent change time across rows of `table` matching `where` —
   specifically, `MAX(lower(sys_period))`. Returns nil when no rows
   match. Monotonic: inserts, updates and tombstones all write a new row
   with a fresh `lower(sys_period)`, so this value increases on every
   change and never otherwise.

   Suitable for building HTTP ETags (see ADR-0008). Lives here, not in
   the consumer namespace, because temporal column vocabulary
   (`sys_period`) is quarantined to the bitemporal layer."
  [ds table where]
  (-> (jdbc/execute-one! ds
                         (sql/format
                          {:select [[[:max [:lower :sys_period]] :t]]
                           :from   [table]
                           :where  where})
                         exec-opts)
      :t))
