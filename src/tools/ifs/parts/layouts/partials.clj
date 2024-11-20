(ns tools.ifs.parts.layouts.partials
  (:require
   [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]))

(defn header
  "Site header"
  []
  [:header.container
   [:div.content
    [:p.logo
     [:img {:src "/images/parts-logo-horizontal.svg"}]]]])

(defn footer
  "Site footer"
  []
  [:footer.container
   [:div.content
    [:div.copyright
     [:p
      "© 2024 "
      [:a {:href "https://a.possible.space"} "A Possible Space Ltd"]
      [:br]
      "Company number 11617016"]]
    [:div.heart
     [:p
      "Made with ❤️ in London, U.K."]]
    [:div.meta
     [:p
      [:strong "Parts"]
      " is free, open source software."
      [:br]
      "See the "
      [:a {:href "https://github.com/apossiblespace/parts"} "source code on GitHub"]
      "."]]]])

(defn waitlist-signup-form
  "Form for signing up for the waiting list"
  [target]
  [:div#signup-form
   [:form {:hx-post "/waitlist-signup"
           :hx-target target
           :hx-swap "innerHTML"}
    [:input {:type "email"
             :id "email"
             :name "email"
             :placeholder "self@you.com"}]
    [:input {:type "hidden"
             :id "__anti-forgery-token"
             :name "__anti-forgery-token"
             :value *anti-forgery-token*}]
    [:input {:type "submit" :value "Sign me up!"}]]])
