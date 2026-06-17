(ns aps.parts.db.json-types
  "JSONB marshalling — making up for JDBC not natively talking to Postgres's
   `jsonb` type, the same gap `range-types` fills for Postgres range types.

   Provides:
   - `->pgobject`: a Clojure map → a `jsonb` PGobject for parameter binding.
   - `?->jsonb`: encode a plain map as a `jsonb` PGobject, pass everything else
     through. Used at the HoneySQL boundary, which would otherwise read a map
     value as a clause map rather than a literal parameter.
   - `parse`: a `jsonb` PGobject → a Clojure map with keyword keys.
   - a next.jdbc `SettableParameter` extension so a Clojure map bound as a SQL
     parameter is sent as `jsonb` automatically. That lets a Part's
     `body_location` (a structured point, see ADR-0013) round-trip without the
     caller converting at every write site — including the history rewrite,
     where an update reads the current row (its `body_location` already parsed
     to a map) and binds it straight back.

   The *read* side (PGobject → map) is owned by `range-types`, which holds the
   single `ReadableColumn PGobject` extension and delegates `jsonb`-typed
   objects to `parse` here. Only one extension may exist for a protocol/type
   pair — a second would silently override the first — so the two type bridges
   share that one dispatch."
  (:require
   [jsonista.core :as json]
   [next.jdbc.prepare :as prepare])
  (:import
   (clojure.lang IPersistentMap)
   (java.sql PreparedStatement)
   (org.postgresql.util PGobject)))

(def ^:private mapper
  "Keyword-keys on the way in; jsonb object keys are strings, so a parsed
   `body_location` comes back as `{:view \"front\" :x 0.42 :y 0.31}`."
  (json/object-mapper {:decode-key-fn keyword}))

(defn ->pgobject
  "Convert a Clojure map to a `jsonb` PGobject ready for parameter binding."
  ^PGobject [m]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-value-as-string m))))

(defn ?->jsonb
  "If `v` is a plain Clojure map, encode it as a `jsonb` PGobject so HoneySQL
   binds it as a literal parameter instead of interpreting it as a clause map.
   Records (e.g. a range value), already-encoded PGobjects, nil, and scalars
   pass through untouched."
  [v]
  (if (and (map? v) (not (record? v)))
    (->pgobject v)
    v))

(defn parse
  "Parse a `jsonb` PGobject into a Clojure map with keyword keys."
  [^PGobject v]
  (json/read-value (.getValue v) mapper))

;; A map bound as a SQL parameter is a jsonb value. A range value is a
;; defrecord (hence an IPersistentMap too), but its own SettableParameter
;; extension is on the concrete class, which wins over this interface
;; extension — so ranges are unaffected.
(extend-protocol prepare/SettableParameter
  IPersistentMap
  (set-parameter [m ^PreparedStatement ps ^long i]
    (.setObject ps i (->pgobject m))))
