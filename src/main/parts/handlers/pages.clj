(ns parts.handlers.pages
  (:require
   [hiccup2.core :refer [html]]
   [parts.views.layouts :as layouts]
   [parts.views.partials :as partials]
   [ring.util.response :as response]))

(defn system-graph
  "Page rendering the graph of a system"
  [_]
  (response/response
   (html
    (layouts/fullscreen
     {:title "System"
      :styles ["/css/flow.css" "/css/style.css"]}
     [:div#root]))))

(defn home-page
  "Page rendered for GET /"
  [_]
  (response/response
   (html
    (layouts/main
     {:title "Mapping tools for IFS practitioners and their clients"
      :styles ["/css/style.css"]}
     [:section.px-4.md:px-16
      [:div.w-full.max-w-7xl.mx-auto
       [:section.max-w-4xl
        [:h1.text-5xl.md:text-6xl.text-primary.font-serif.leading-tight.font-normal
         "Understand your clients' parts and their relationships."]]
       [:div.flex.flex-col.lg:flex-row.lg:justify-between.gap-0.lg:gap-12
        [:section.w-full.text-center
         [:p
          [:img.w-full.max-w-md.mx-auto {:src "/images/system-illustration.svg"}]]]
        [:section.w-full.max-w-md.mx-auto.my-8
         [:h3.mb-4
          [:strong.font-bold "Parts"]
          " is a mapping tool for IFS practitioners to keep track of, visualise, and explore the relationships between their clients' parts."]
         [:p.mb-6
          [:strong.font-bold "Parts"]
          " is being actively developed, and we would love to have your feedback! Please enter your email below to join the private beta test."]
         (partials/waitlist-signup-form)]]]]
     [:section.bg-bg-secondary.px-4.md:px-16
      [:div.w-full.max-w-7xl.mx-auto.py-8.md:py-16
       [:h3.text-2xl.font-bold.mb-6
        "Who made this?"]
       [:div.flex.flex-col.md:flex-row.justify-between.gap-4.md:gap-12
        [:div.relative.pl-32.md:pl-40.min-h-32
         [:img.absolute.top-0.left-0.h-32.w-32.rounded-full {:src "/images/avatars/gosha.svg"}]
         [:p.m-0
          [:strong.font-bold "Gosha Tcherednitchenko"]
          " is a software engineer with 20 years experience building for the Web."
          [:br]
          "Online: "
          [:a.text-primary.hover:underline {:href "https://gosha.net"} "Website"]
          ", "
          [:a.text-primary.hover:underline {:href "https://bsky.app/profile/gosha.net"} "Bluesky"]
          "."]]
        [:div.relative.pl-32.md:pl-40.min-h-32
         [:img.absolute.top-0.left-0.h-32.w-32.rounded-full {:src "/images/avatars/tingyi.svg"}]
         [:p.m-0
          [:strong.font-bold "Ting-yi Lai"]
          " is an IFS Level 1 trained art psychotherapist, focusing on trauma."]]]]]))))
