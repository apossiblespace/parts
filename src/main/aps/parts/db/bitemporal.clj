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

   - TT-current:      keep rows still believed — not superseded by a later
                      record. Implemented clock-free as
                      `upper(sys_period) = infinity` (see `tt-current`), so
                      it also holds for rows written earlier in the same
                      transaction.
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
   [next.jdbc.result-set :as rs])
  (:import
   (java.time OffsetDateTime)
   (java.time.temporal ChronoUnit)))

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

(def ^:private vt-open
  "HoneySQL predicate: the row's valid-time extends to infinity, i.e. the
   entity exists from its start onward and has not been retracted.
   `upper(valid_at) = 'infinity'`. Unlike `vt-contains`, this never names
   `now()`, so it is immune to Postgres freezing `now()` at transaction
   start — see `live-rows`."
  [:= [:upper :valid_at] [:cast [:inline "infinity"] :timestamptz]])

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

(defn- current-where
  "WHERE for the currently-in-effect row: id + TT-current + VT-open,
   plus an optional caller-supplied `scope` fragment (e.g. `[:= :map_id m]`)
   that confines the match to an authorisation boundary. A row that exists
   but falls outside `scope` simply doesn't match — the caller sees
   not-found rather than touching a row it isn't allowed to.

   VT-open (`vt-open`) rather than `valid_at @> now()` on purpose: Postgres
   freezes `now()` at transaction start, so a row written earlier in the
   same transaction — its `valid_at` lower bound is wall-clock, later than
   the frozen `now()` — would be invisible to the next write in that
   transaction. Same reasoning as `live-rows` on the read side. A retracted
   entity has a bounded `valid_at` upper, so it still correctly doesn't
   match."
  [id scope]
  (cond-> [:and
           [:= :id id]
           tt-current
           vt-open]
    scope (conj scope)))

(defn- find-current-row [tx table id scope]
  (first
   (jdbc/execute! tx
                  (sql/format {:select [:*]
                               :from   [table]
                               :where  (current-where id scope)})
                  exec-opts)))

(defn- write-ts
  "The single wall-clock instant one sequenced write operates at, given the
   `current` row it is superseding. Wall clock rather than SQL `now()` so
   that several writes to one entity inside one transaction each get their
   own instant (`now()` is frozen at transaction start and would collapse
   them into inverted or empty ranges).

   Clamped: if the wall clock is at or behind the row's bounds (NTP stepping
   the clock back between writes), returns a tick just past those bounds so
   every derived range stays well-formed. The lie is bounded at a
   microsecond; the alternative is a rejected write.

   Truncated to microseconds — Postgres timestamptz resolution. The same
   instant reaches Postgres on two paths (a bound JDBC parameter in
   `close-current-row!`, an ISO string inside a range literal from
   `range-types`), and the two paths round sub-microsecond digits
   differently; a µs-aligned value serializes identically on both."
  [current]
  (let [now-ts (.truncatedTo (OffsetDateTime/now) ChronoUnit/MICROS)
        floor  (->> [(:lower (:valid_at current))
                     (:lower (:sys_period current))]
                    (filter #(instance? OffsetDateTime %))
                    sort
                    last)]
    (if (and floor (not (.isAfter now-ts ^OffsetDateTime floor)))
      (.plusNanos ^OffsetDateTime floor 1000)
      now-ts)))

(defn- close-current-row!
  "Advance the `sys_period` upper bound of the currently-in-effect row to
   `close-ts` (the operation's `write-ts`). Matches by id + TT-current +
   VT-open (+ optional `scope`) — other open-sys_period rows
   (historical-belief slices from prior updates) keep their state.

   Optimistically locked: `current` is the row the operation just read, and
   the UPDATE additionally matches its exact version by `lower(sys_period)`.
   A concurrent transaction that superseded the row between our read and
   this UPDATE leaves nothing to match — anything but exactly one closed
   row means our merge base is stale, and the only safe outcome is a
   `:conflict` the caller can surface — 409 standalone, or rolled into the
   batch's 422 `:batch-failure` (as `:cause-type`); either way the client
   re-syncs."
  [tx table id scope current close-ts]
  (let [sys-lower (:lower (:sys_period current))
        where     (cond-> (current-where id scope)
                    (instance? OffsetDateTime sys-lower)
                    (conj [:= [:lower :sys_period]
                           [:cast sys-lower :timestamptz]]))
        result    (jdbc/execute-one! tx
                                     (sql/format {:update table
                                                  :set    {:sys_period [:tstzrange
                                                                        [:lower :sys_period]
                                                                        [:cast close-ts :timestamptz]
                                                                        [:inline "[)"]]}
                                                  :where  where}))]
    (when-not (= 1 (:next.jdbc/update-count result))
      (throw (ex-info "Entity was modified by a concurrent change"
                      {:type :conflict :id id :table table})))))

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
     :valid-at  (optional)  TstzRange (defaults to [now, infinity))
     :sys-from  (optional)  OffsetDateTime lower bound for `sys_period`
                            (defaults to now). The sequenced writes pass
                            their `write-ts` so every range minted by one
                            operation shares one instant."
  [tx table row {:keys [actor-id valid-at sys-from]}]
  (assert actor-id "actor-id is required")
  (assert (:id row) "row :id is required")
  (let [now      (OffsetDateTime/now)
        valid-at (or valid-at (range/tstzrange now :infinity))
        sys-at   (range/tstzrange (or sys-from now) :infinity)
        full-row (assoc row
                        :valid_at   (range/->pgobject valid-at)
                        :sys_period (range/->pgobject sys-at)
                        :actor_id   actor-id)
        inserted (first (jdbc/execute! tx
                                       (sql/format {:insert-into table
                                                    :values      [full-row]
                                                    :returning   :*})
                                       exec-opts))]
    (internal-cols inserted)))

(defn update!
  "Sequenced update — the entity's values change starting *now*.

   Inside one transaction (canonical split-retract-insert; Snodgrass Ch 7),
   all at one wall-clock instant `t` (see `write-ts` — per-operation, so
   several updates to one entity in one transaction stay well-formed):
     1. Find the currently-believed row where `id = ?`.
     2. Close its `sys_period` upper bound at `t`.
     3. Insert a *historical* row preserving the previous values over
        `valid_at = [old-lower, t)` — so as-of-valid queries at past
        instants still return the entity (with the old values).
     4. Insert a *new* row with merged values over `valid_at = [t, infinity)`.

   Returns the new row (without internal cols).

   Opts:
     :actor-id  (required)
     :scope     (optional) extra WHERE fragment ANDed into the row lookup —
                e.g. `[:= :map_id m]`. A row outside the scope is treated as
                not-found, so the caller can't update a row beyond its
                authorisation boundary.

   For asserting a fact about a non-overlapping past valid time, use
   `insert!` directly with an explicit `:valid-at`."
  [tx table id changes {:keys [actor-id scope]}]
  (assert actor-id "actor-id is required")
  (with-tx tx
    (fn [tx]
      (let [current (find-current-row tx table id scope)]
        (when-not current
          (throw (ex-info "Row not found in current state"
                          {:type :not-found :id id :table table})))
        (let [now-ts     (write-ts current)
              old-lower  (:lower (:valid_at current))
              old-values (internal-cols current)
              ;; {:id id} re-asserts the row key: a sequenced update changes
              ;; attributes, never identity — `changes` can't relocate the row.
              new-values (merge old-values changes {:id id})]
          (close-current-row! tx table id scope current now-ts)
          ;; The historical slice is always non-empty: `write-ts` clamps
          ;; strictly past the row's `valid_at` lower bound.
          (insert! tx table old-values
                   {:actor-id actor-id
                    :valid-at (range/tstzrange old-lower now-ts "[)")
                    :sys-from now-ts})
          (insert! tx table new-values
                   {:actor-id actor-id
                    :valid-at (range/tstzrange now-ts :infinity "[)")
                    :sys-from now-ts}))))))

(defn correction!
  "Bitemporal correction — rewrite *belief* about the currently-in-effect row
   without changing its valid-time extent.

   Use case: 'I recorded this wrong last session — the part was always a
   Manager, not a Firefighter, for the same valid time we already have.'

   Contrast with `update!`, which says 'from now on the value changes'
   (introducing a new valid-time slice). `correction!` leaves the valid_at
   range untouched; only `sys_period` advances.

   Inside one transaction, at one wall-clock instant `t` (see `write-ts`):
     1. Find the currently-in-effect row (id + TT-current + VT-open).
     2. Close its `sys_period` upper at `t`.
     3. Insert a corrected row with the *same* `valid_at` and the merged values."
  [tx table id changes {:keys [actor-id scope]}]
  (assert actor-id "actor-id is required")
  (with-tx tx
    (fn [tx]
      (let [current (find-current-row tx table id scope)]
        (when-not current
          (throw (ex-info "Row not found in current state"
                          {:type :not-found :id id :table table})))
        (let [now-ts     (write-ts current)
              old-valid  (:valid_at current)
              old-values (internal-cols current)
              new-values (merge old-values changes {:id id})]
          (close-current-row! tx table id scope current now-ts)
          (insert! tx table new-values
                   {:actor-id actor-id
                    :valid-at old-valid
                    :sys-from now-ts}))))))

(defn retract!
  "Retract a bitemporal entity — the entity no longer exists from `now` on.

   Closes the currently-active row's `sys_period` and inserts a successor
   with `valid_at` upper-bounded at the operation's wall-clock instant
   (see `write-ts`). After this, `as-of-now` queries
   no longer return the entity, but `as-of-valid` at any past instant still
   does.

   Returns `{:retracted true :id id}` or `{:retracted false :id id}` if no
   current row was found (including a row that exists but falls outside an
   optional `:scope` WHERE fragment — same authorisation-boundary semantics
   as `update!`)."
  [tx table id {:keys [actor-id scope]}]
  (assert actor-id "actor-id is required")
  (with-tx tx
    (fn [tx]
      (if-let [current (find-current-row tx table id scope)]
        (let [now-ts       (write-ts current)
              old-valid    (:valid_at current)
              successor    (internal-cols current)
              closed-valid (range/tstzrange (:lower old-valid) now-ts "[)")]
          (close-current-row! tx table id scope current now-ts)
          (insert! tx table successor {:actor-id actor-id
                                       :valid-at closed-valid
                                       :sys-from now-ts})
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

(defn live-rows
  "Currently-existing rows: current-belief (TT-current) AND not retracted
   (`valid_at` open to infinity). Differs from `as-of-now` in one crucial
   way — it never compares against SQL `now()`, which Postgres freezes at
   transaction start. So `live-rows` sees rows inserted *earlier in the same
   transaction*, where `as-of-now` would not (their `valid_at` lower bound is
   later than the frozen `now()`). Use it to validate references between rows
   created together in one batch — e.g. confirming a Relationship's endpoints
   are Parts created earlier in the same change-batch transaction."
  [ds table where]
  (query ds table where (into [:and tt-current vt-open])))

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

(defn- endpoint->instant
  "A range endpoint as an OffsetDateTime, or nil for an unbounded end
   (`:infinity` / `:-infinity`) — so a reader can hand out 'no boundary' as nil
   rather than a sentinel keyword."
  [e]
  (when (instance? java.time.OffsetDateTime e) e))

(defn- ->version
  "Shape a history row for a caller outside the temporal layer: business columns
   plus the valid interval surfaced as `:valid_from` / `:valid_to` (an unbounded
   end is nil). The internal columns are stripped — we read `valid_at`'s bounds
   out, then drop it, so the temporal vocabulary never leaves this namespace."
  [row]
  (let [{:keys [lower upper]} (:valid_at row)]
    (assoc (internal-cols row)
           :valid_from (endpoint->instant lower)
           :valid_to   (endpoint->instant upper))))

(defn history
  "Every current-belief valid-time version of the rows matching `where`, ordered
   by valid-time — a Map's full history as we now understand it (TT-current,
   folded across all VT). Powers the data-subject export (ADR-0010).

   Unlike the time-slice readers, this *surfaces* each version's valid interval,
   because the history is the result: each row carries `:valid_from` /
   `:valid_to`, an unbounded (`infinity`) end rendered as nil ('still in
   effect'). Superseded beliefs from `correction!` are not returned (TT-current
   only); a retracted entity still appears, with a closed `:valid_to`."
  ([ds table] (history ds table nil))
  ([ds table where]
   (->> (jdbc/execute! ds
                       (sql/format {:select   [:*]
                                    :from     [table]
                                    :where    (if where [:and tt-current where] tt-current)
                                    :order-by [[[:lower :valid_at] :asc]]})
                       exec-opts)
        (map ->version))))

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
