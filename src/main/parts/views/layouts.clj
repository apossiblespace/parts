(ns parts.views.layouts
  (:require
   [hiccup2.core :refer [html]]
   [parts.views.partials :as partials]))

(defn main
  "Fundamental application layout"
  [title & content]
  (html
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:meta {:name "description" :content "Parts is a mapping tool for IFS practitioners to keep track of, visualise, and explore the relationships between their clients’ parts."}]
     [:meta {:name "theme-color" :content "#62a294"}]
     [:link {:rel "icon" :sizes "192x192" :href "/images/icons/favicon.png"}]
     [:link {:rel "apple-touch-icon" :href "/images/icons/favicon.png"}]
     [:title (str title " — Parts")]
     [:link {:rel "stylesheet" :href "/css/style.css"}]
     [:link {:rel "stylesheet" :href "/css/flow.css"}]
     [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
     [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin true}]
     [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=DM+Serif+Display:ital@0;1&display=swap"}]
     [:script {:defer true
               :data-domain "parts.ifs.tools"
               :src "https://plausible.io/js/script.outbound-links.tagged-events.js"}]]
    [:body
     (partials/header)
     content
     (partials/footer)
     [:script {:src "/js/main.js"}]]))
