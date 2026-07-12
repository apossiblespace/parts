(ns aps.parts.frontend.dates
  "Shared date coercion for frontend components.")

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
