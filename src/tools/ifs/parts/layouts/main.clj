(ns tools.ifs.parts.layouts.main
  (:require
   [hiccup2.core :refer [html]]))

(defn layout
  "Fundamental application layout"
  [title & content]
  (html
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:meta {:name "description" :content "Parts is a mapping tool for IFS practitioners to keep track of, visualise, and explore the relationships between their clients’ parts."}]
     [:link {:rel "icon" :sizes "192x192" :href "/images/icons/favicon.png"}]
     [:link {:rel "apple-touch-icon" :href "/images/icons/favicon.png"}]
     [:title (str title " — Parts")]
     [:link {:rel "stylesheet" :href "/css/style.css"}]
     [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
     [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin true}]
     [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=DM+Serif+Display:ital@0;1&display=swap"}]
     [:script {:src "/js/main.js"}]
     [:script {:defer true
               :data-domain "parts.ifs.tools"
               :src "https://plausible.io/js/script.outbound-links.tagged-events.js"}]]
    [:body
     [:section.container content]]))
