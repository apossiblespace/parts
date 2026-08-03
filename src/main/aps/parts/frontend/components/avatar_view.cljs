(ns aps.parts.frontend.components.avatar-view
  "Pure logic for the avatar placeholder. Deliberately free of uix/React
   and npm imports so it can be unit-tested directly under the cljs
   suite — the same split as account-view."
  (:require
   [clojure.string :as str]))

(defn initial-of
  "Uppercased first character of `display-name` for the avatar
   placeholder. Reads the first code point, not the first UTF-16 unit,
   so a name starting with an emoji or other astral-plane character
   keeps the whole character — `subs` would slice its surrogate pair
   in half."
  [display-name]
  (some-> display-name not-empty (.codePointAt 0)
          js/String.fromCodePoint str/upper-case))
