(ns aps.parts.frontend.dates
  "Shared date coercion and formatting for frontend components.")

(def short-date-format
  "\"4 Jul\" / \"Jul 4\" — the browser locale decides the shape; no date lib."
  (js/Intl.DateTimeFormat. js/undefined #js {:day "numeric" :month "short"}))

(def medium-date-format
  "\"4 Jul 2026\" (locale-shaped)."
  (js/Intl.DateTimeFormat. js/undefined #js {:dateStyle "medium"}))

(def long-date-format
  "\"4 July 2026\" (locale-shaped)."
  (js/Intl.DateTimeFormat. js/undefined
                           #js {:day "numeric" :month "long" :year "numeric"}))

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

(defn relative-past
  "A relative phrase for how long ago `d` was: \"today\" / \"yesterday\" /
   \"3 days ago\" / \"2 weeks ago\", falling back to \"on <date>\" once
   it's a month or more old. Nil when unparseable."
  [d]
  (when-let [^js dt (->js-date d)]
    (let [days (js/Math.floor (/ (- (js/Date.now) (.getTime dt)) 86400000))]
      (cond
        (<= days 0) "today"
        (= days 1)  "yesterday"
        (< days 7)  (str days " days ago")
        (< days 14) "1 week ago"
        (< days 30) (str (js/Math.floor (/ days 7)) " weeks ago")
        :else       (str "on " (format-date medium-date-format dt))))))
