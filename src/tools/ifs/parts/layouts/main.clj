(ns tools.ifs.parts.layouts.main
  (:require [hiccup.page :refer [html5 include-css include-js]]))

(defn layout
  "Fundamental application layout"
  [title & content]
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:meta {:name "description" :content "Toolkit for IFS practitioners and their clients"}]
    [:link {:rel "icon" :sizes "192x192" :href "/images/icons/favicon.png"}]
    [:link {:rel "apple-touch-icon" :href "/images/icons/favicon.png"}]
    [:title title]
    (include-css "/css/style.css")
    (include-js "/js/main.js")]
   [:body
    [:secton#container content]
    [:script
     "window.addEventListener('load', function () { tools.ifs.parts.core.init(); });"]]))
