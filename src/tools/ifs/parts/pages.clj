(ns tools.ifs.parts.pages
  (:require [hiccup.core :refer [html]]
            [tools.ifs.parts.layouts.main :refer [layout]]
            [tools.ifs.parts.layouts.partials :refer [header footer]]
            [ring.util.response :as response]))

(defn system-graph
  "Page rendering the graph of a system"
  [system-id]
  (-> (response/response
       (html
           (layout "System"
                   (header)
                   [:div
                    [:h2 "System"]]
                   [:div#chart]
                   (footer))))
      (response/content-type "text/html")))

(defn home-page
  "Page rendered for GET /"
  [_]
  (-> (response/response
       (html
           (layout "Home Page"
                   (header)
                   [:div
                    [:h1
                     {:align "center"}
                     "Have better conversations with your clients"]
                    [:h3.hook
                     {:align "center"}
                     [:strong "Parts"]
                     " is a tool for IFS practitioners to keep track of, visualise, and explore the relationships between their clients’ parts."]]
                   (footer))))
      (response/content-type "text/html")))
