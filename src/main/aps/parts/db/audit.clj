(ns aps.parts.db.audit
  "Explicit audit_log writes for non-temporal tables.

   The audit_log trigger fires only on the temporal tables (parts,
   relationships, map_metadata), so anything else that must leave an audit
   trail — Sessions being the first (ADR-0014, \"Writes, audit, and data
   lifecycle\") — records its own row through here. Same shape the trigger
   writes, so consumers (the operator active-user metric in
   `aps.parts.stats`, which counts audit rows regardless of table) need no
   special cases."
  (:require
   [aps.parts.db :as db]
   [aps.parts.db.json-types :as json-types]))

(defn record!
  "Insert one audit_log row. `op` is :insert, :update, or :delete; `row-id`
   the affected row's id; `before`/`after` optional row snapshots (clj maps,
   bound as jsonb). Runs on `tx` so the audit commits or rolls back with the
   operation it describes."
  [tx {:keys [actor-id table op row-id before after]}]
  ;; Map values must cross the HoneySQL boundary as jsonb parameters —
  ;; bare, they'd be read as SQL clause maps (see db.json-types).
  (db/insert! :audit_log
              {:actor_id   (db/->uuid actor-id)
               :table_name (name table)
               :operation  (case op :insert "I" :update "U" :delete "D")
               :row_pk     (json-types/?->jsonb {:id (str row-id)})
               :before_row (json-types/?->jsonb before)
               :after_row  (json-types/?->jsonb after)}
              tx))
