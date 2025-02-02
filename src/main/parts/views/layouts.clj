(ns parts.views.layouts
  (:require
   [hiccup2.core :refer [html]]
   [parts.views.partials :as partials]))

(defn main
  "Fundamental application layout"
  [options & content]
  (html
   (partials/head options)
    [:body
     (partials/header)
     content
     (partials/footer)
     (partials/scripts options)]))

(defn fullscreen
  "Full-screen layout without header or footer"
  [options & content]
  (html
   (partials/head options)
   [:body
    content
    (partials/scripts options)]))
