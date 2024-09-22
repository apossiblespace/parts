(ns apossiblespace.parts.pages
  (:require [hiccup.core :refer [html]]
            [apossiblespace.parts.layouts.main :refer [layout]]
            [apossiblespace.parts.layouts.partials :refer [header footer]]
            [ring.util.response :as response]))

(defn home-page [_]
  (-> (response/response
       (html
           (layout "Home Page"
                   (header)
                   [:div
                    [:h2 "Hello this is a test"]
                    [:p "This page is rendered using Hiccup 2.0"]]
                   (footer))))
      (response/content-type "text/html")))
