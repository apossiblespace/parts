(ns tools.ifs.parts.layouts.partials
  (:require
   [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]))

(defn header
  "Site header"
  []
  [:header
   [:p {:align "center"}
    [:img {:src "/images/parts-logo-horizontal.svg"}]]])

(defn footer
  "Site footer"
  []
  [:footer
   [:div.copyright
    [:p
     "© 2024 "
     [:a {:href "https://a.possible.space"} "A Possible Space Ltd"]
     [:br]
     "Company number 11617016"]]
   [:div.meta
    [:p
     [:strong "Parts"]
     " is free, open source software."
     [:br]
     "See the "
     [:a {:href "https://github.com/apossiblespace/parts"} "source code on GitHub"]
     "."]]])

(defn waitlist-signup-form
  "Form for signing up for the waiting list"
  [target]
  [:div#signup-form
   [:form {:hx-post "/waitlist-signup"
           :hx-target target
           :hx-swap "outerHTML"}
    [:input {:type "email"
             :id "email"
             :name "email"
             :placeholder "self@you.com"}]
    [:input {:type "hidden"
             :id "__anti-forgery-token"
             :name "__anti-forgery-token"
             :value *anti-forgery-token* }]
    [:input {:type "submit" :value "Sign me up!"}]]])
