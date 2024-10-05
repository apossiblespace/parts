(ns tools.ifs.parts.pages
  (:require [hiccup2.core :refer [html]]
            [tools.ifs.parts.layouts.main :refer [layout]]
            [tools.ifs.parts.layouts.partials :refer [header footer waitlist-signup-form]]
            [ring.util.response :as response]))

(defn system-graph
  "Page rendering the graph of a system"
  [system-id]
  (response/response
   (html
       (layout "System"
               (header)
               [:div [:h2 "System"]]
               [:div#chart]
               (footer)))))

(defn home-page
  "Page rendered for GET /"
  [_]
  (-> (response/response
       (html
           (layout
            "Home Page"
            (header)
            [:section.hero
             [:h1
              {:align "center"}
              "Understand your clients’ parts and their relationships"]]
            [:div.illustration
             [:p
              [:img {:src "/images/system-illustration.svg"}]]
             [:h3.hook
              {:align "center"}
              [:strong "Parts"]
              " is a tool for IFS practitioners to keep track of, visualise, and explore the relationships between their clients’ parts."]]
            [:section.signup
             [:p "Please enter your email below to join the private beta test."]
             (waitlist-signup-form ".signup")]
            (footer))))))
