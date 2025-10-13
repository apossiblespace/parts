(ns aps.parts.views.layouts
  (:require
   [hiccup2.core :refer [html]]
   [aps.parts.views.partials :as partials]))

(def default-options
  {:header true
   :footer true
   :description "Parts is a mapping tool for IFS practitioners to keep track of, visualise, and explore the relationships between their clients’ aps.parts."})

(defn- base-layout
  "Generic layout renderer based on options.
   Options map can include:
   :title       - page title
   :description - meta description
   :styles      - additional stylesheets
   :scripts     - additional scripts
   :header      - whether header should be rendered
   :footer      - whether footer should be rendered"
  [options & content]
  (let [options (merge default-options options)]
    (html
     (partials/head options)
     [:body.font-sans.bg-gray-50.text-gray-900
      (when (:header options) (partials/header))
      content
      (when (:footer options) (partials/footer))
      (partials/scripts options)])))

(defn main
  "Fundamental application layout"
  [options & content]
  (base-layout options content))

(defn fullscreen
  "Full-screen layout without header or footer"
  [options & content]
  (base-layout
   (merge options {:header false :footer false})
   content))
