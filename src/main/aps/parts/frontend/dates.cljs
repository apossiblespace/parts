(ns aps.parts.frontend.dates
  "Shared date coercion and formatting for frontend components.")

(def short-date-format
  "\"4 Jul\" / \"Jul 4\" — the browser locale decides the shape; no date lib."
  (js/Intl.DateTimeFormat. js/undefined #js {:day "numeric" :month "short"}))

(def medium-date-format
  "\"4 Jul 2026\" (locale-shaped)."
  (js/Intl.DateTimeFormat. js/undefined #js {:dateStyle "medium"}))

(defn ->js-date
  "Coerce a date-ish value to a valid js/Date, or nil. Transit
   deserialises Java `Date`/`Instant`/`Timestamp` to a `js/Date` on the
   cljs side; this also accepts an ISO string defensively, and the nil
   for anything unparseable lets callers render nothing rather than
   \"Invalid Date\"."
  [d]
  (when d
    (let [^js dt (if (instance? js/Date d) d (js/Date. d))]
      (when-not (js/isNaN (.getTime dt))
        dt))))

(defn format-date
  "Format a date-ish value with an Intl.DateTimeFormat, or nil when
   unparseable — render nothing rather than \"Invalid Date\"."
  [^js format d]
  (when-let [^js dt (->js-date d)]
    (.format format dt)))
