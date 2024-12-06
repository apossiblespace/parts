(ns tools.ifs.parts.pages
  (:require
   [hiccup2.core :refer [html]]
   [ring.util.response :as response]
   [tools.ifs.parts.layouts.main :refer [layout]]
   [tools.ifs.parts.layouts.partials :refer [footer header waitlist-signup-form]]))

(defn system-graph
  "Page rendering the graph of a system"
  [system-id]
  (response/response
    (html
      (layout "System"
              [:div [:h2 "System"]]
              [:div#chart]))))

(defn home-page
  "Page rendered for GET /"
  [_]
  (-> (response/response
        (html
          (layout
            "Mapping tools for IFS practitioners and their clients"
            [:section.container
             [:div.content
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
                (waitlist-signup-form ".signup")]]]]
            [:section.aboutus.container
             [:div.content
              [:h3
               "Who made this?"]
              [:div.person-cards
               [:div.person-card
                [:img {:src "/images/avatars/gosha.svg"}]
                [:p
                 [:strong "Gosha Tcherednitchenko"]
                 " is a software engineer with 20 years experience building for the Web."
                 [:br]
                 "Online: "
                 [:a {:href "https://gosha.net"} "Website"]
                 ", "
                 [:a {:href "https://bsky.app/profile/gosha.net"} "Bluesky"]
                 "."]]
               [:div.person-card
                [:img {:src "/images/avatars/tingyi.svg"}]
                [:p
                 [:strong "Ting-yi Lai"]
                 " is an IFS Level 1 trained art psychotherapist, focusing on trauma."]]]]])))))
