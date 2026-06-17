(ns aps.parts.db.range-types
  "Bitemporal range type marshalling, aka making up for JDBC not being able to
   natively talk to Postgres's `tstzrange` type. JDBC will surface a `PGobject`
   that we have to then parse.

   Provides:
   - A `TstzRange` record + helpers `tstzrange`, `range-contains?`.
   - next.jdbc protocol extensions so PostgreSQL `tstzrange` values round-trip
     transparently to / from Clojure (read-column / set-parameter).

   HoneySQL operator registration for `@>` / `<@` / `&&` / `-|-` lives with the
   only consumer (`aps.parts.db.bitemporal`), not here.

   Endpoints can be `java.time.OffsetDateTime` for bounded ends, or the
   sentinel keywords `:infinity` / `:-infinity` for unbounded ends so as to
   mirror the values emitted by Postgres."
  (:require
   [aps.parts.db.json-types :as json-types]
   [clojure.string :as str]
   [next.jdbc.prepare :as prepare]
   [next.jdbc.result-set :as rs])
  (:import
   (java.sql PreparedStatement)
   (java.time OffsetDateTime ZoneOffset)
   (java.time.format DateTimeFormatter DateTimeFormatterBuilder)
   (org.postgresql.util PGobject)))

;; -- Range value ------------------------------------------------------------

(defrecord TstzRange [lower upper bounds])

;; NOTE: See the postgres docs for the possible formats for bounds:
;; https://www.postgresql.org/docs/16/rangetypes.html#RANGETYPES-CONSTRUCT
(defn tstzrange
  "Build a bitemporal range. `bounds` defaults to `\"[)\"` (closed-open)."
  ([lower upper] (->TstzRange lower upper "[)"))
  ([lower upper bounds] (->TstzRange lower upper bounds)))

;; -- Parsing / rendering ---------------------------------------------------

(def ^:private pg-formatter
  ;; Postgres tstzrange output looks like:
  ;;   "2026-05-11 16:05:29.305848+01"
  ;; (space separator, optional fractional seconds, offset may be +HH / +HH:MM / Z).
  (-> (DateTimeFormatterBuilder.)
      (.appendPattern "yyyy-MM-dd HH:mm:ss")
      (.appendFraction java.time.temporal.ChronoField/NANO_OF_SECOND 0 9 true)
      (.appendOffset "+HH:MM" "Z")
      (.toFormatter)))

(def ^:private pg-formatter-no-colon
  (-> (DateTimeFormatterBuilder.)
      (.appendPattern "yyyy-MM-dd HH:mm:ss")
      (.appendFraction java.time.temporal.ChronoField/NANO_OF_SECOND 0 9 true)
      (.appendOffset "+HHMM" "Z")
      (.toFormatter)))

(defn- parse-pg-timestamp [s]
  ;; Try formats Postgres can emit. `pg_dump`-style strings sometimes lack the
  ;; offset colon; both forms occur in the wild.
  (try
    (OffsetDateTime/parse s pg-formatter)
    (catch Exception _
      (try
        (OffsetDateTime/parse s pg-formatter-no-colon)
        (catch Exception _
          ;; Last-resort fallback: ISO with `T` separator.
          (OffsetDateTime/parse (str/replace-first s " " "T")
                                DateTimeFormatter/ISO_OFFSET_DATE_TIME))))))

(defn- read-endpoint [s]
  (cond
    (str/blank? s) :-infinity
    (= s "infinity") :infinity
    (= s "-infinity") :-infinity
    :else (parse-pg-timestamp s)))

(defn- write-endpoint [v]
  (cond
    (= v :infinity) "infinity"
    (= v :-infinity) "-infinity"

    (instance? OffsetDateTime v)
    (.format ^OffsetDateTime v DateTimeFormatter/ISO_OFFSET_DATE_TIME)

    (instance? java.time.Instant v)
    (.format (.atOffset ^java.time.Instant v ZoneOffset/UTC)
             DateTimeFormatter/ISO_OFFSET_DATE_TIME)

    :else (str v)))

(defn parse-tstzrange
  "Parse a Postgres tstzrange string literal into a TstzRange (or nil for
   `empty`)."
  [^String s]
  (cond
    (nil? s) nil
    (= s "empty") nil
    :else
    (let [lb      (subs s 0 1)
          rb      (subs s (dec (count s)))
          inner   (subs s 1 (dec (count s)))
          comma   (.indexOf inner ",")
          lo      (subs inner 0 comma)
          hi      (subs inner (inc comma))
          unquote (fn [^String v]
                    (if (and (str/starts-with? v "\"") (str/ends-with? v "\""))
                      (-> v (subs 1 (dec (count v))) (str/replace "\\\"" "\""))
                      v))]
      (->TstzRange (read-endpoint (unquote lo))
                   (read-endpoint (unquote hi))
                   (str lb rb)))))

(defn render-tstzrange
  "Render a TstzRange as a Postgres tstzrange literal."
  [{:keys [lower upper bounds]}]
  (str (subs bounds 0 1)
       (write-endpoint lower) ","
       (write-endpoint upper)
       (subs bounds 1 2)))

;; -- PGobject bridge --------------------------------------------------------

(defn ->pgobject
  "Convert a TstzRange to a PGobject ready for parameter binding.
   Use this at the HoneySQL boundary — HoneySQL would otherwise treat the
   TstzRange record's keys as a clause map."
  ^PGobject [r]
  (doto (PGobject.)
    (.setType "tstzrange")
    (.setValue (render-tstzrange r))))

(extend-protocol prepare/SettableParameter
  TstzRange
  (set-parameter [v ^PreparedStatement ps ^long i]
    (.setObject ps i (->pgobject v))))

;; This is the single `ReadableColumn PGobject` extension in the codebase —
;; only one may exist, so it routes every PGobject type we marshal. `jsonb` is
;; delegated to `json-types` (see its docstring); unknown types pass through
;; untouched.
(defn- read-pgobject [^PGobject v]
  (case (.getType v)
    "tstzrange" (parse-tstzrange (.getValue v))
    "jsonb"     (json-types/parse v)
    v))

(extend-protocol rs/ReadableColumn
  PGobject
  (read-column-by-label [v _label]       (read-pgobject v))
  (read-column-by-index [v _rsmeta _idx] (read-pgobject v)))

;; -- Predicates -------------------------------------------------------------

(defn- before-or-eq? [^OffsetDateTime a ^OffsetDateTime b]
  (not (.isAfter a b)))

(defn range-contains?
  "Does the range contain instant `t`? Mirrors PG's `@>` semantics for
   closed-open `[)` ranges (the default)."
  [{:keys [lower upper bounds]} ^OffsetDateTime t]
  (let [[lb rb]  [(first bounds) (second bounds)]
        lower-ok (cond
                   (= lower :-infinity) true
                   (= lb \[)            (before-or-eq? lower t)
                   :else                (.isBefore lower t))
        upper-ok (cond
                   (= upper :infinity) true
                   (= rb \])           (before-or-eq? t upper)
                   :else               (.isBefore t upper))]
    (and lower-ok upper-ok)))

(defn current?
  "Is the range still 'now-current'? (i.e. `@> now()`)."
  [r]
  (range-contains? r (OffsetDateTime/now)))
