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
            "Mapping tools for IFS practitioners and their clients"
            (header)
            [:section.hero
             [:h1
              "Understand your clients’ parts and their relationships."]]
            [:div.main
             [:section.illustration
              [:p
               [:img {:src "/images/system-illustration.svg"}]]]
             [:section.signup
              [:h3.hook
               [:strong "Parts"]
               " is a mapping tool for IFS practitioners to keep track of, visualise, and explore the relationships between their clients’ parts."]
              [:p
               [:strong "Parts"]
               " is being actively developed, and we would love to have your feedback! Please enter your email below to join the private beta test."]
              (waitlist-signup-form ".signup")]]
            [:section.aboutus
             [:h3
              "Who made this?"]
             [:div.person-cards
              [:div.person-card
               [:img {:src "/images/avatars/tingyi.jpg"}]
               [:p
                [:strong "Ting-yi Lai"]
                " is an IFS Level 1 trained art psychotherapist focused on trauma"]]
              [:div.person-card
               [:img {:src "/images/avatars/gosha.jpg"}]
               [:p
                [:strong "Gosha Tcherednitchenko"]
                " is a software engineer who's been building Web applications for close to 20 years"]]]]
            (footer))))))
