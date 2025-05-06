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
      :styles ["/css/flow.css" "/css/style.css"]}
     [:section.px-4.md:px-16
      [:div
       {:class "grid grid-cols-1 md:grid-cols-2 gap-12 w-full mx-auto"}
       [:div
        [:h1.text-5xl.md:text-6xl.text-primary.font-serif.leading-tight.font-normal
         "Understand your clients' parts and their relationships."]
        [:h3.mb-4
         [:strong.font-bold "Parts"]
         " is a mapping tool for IFS practitioners to keep track of, visualise, and explore the relationships between their clients' parts."]
        [:div {:class "grid grid-cols-1 md:grid-cols-2 gap12 w-full"}
         [:a {:href "#signup"
              :class "btn btn-primary"}
          "Join the Founding Circle"]
         [:a {:href "/system"
              :class "btn"}
          "Try the playground"]]]
       [:div#root.demo.minimal]]]
     [:section
      {:class "py-20 text-white",
       :id "signup",
       :style {:background-color "#4eb48a"}}
      [:div
       {:class
        "container max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 text-center"}
       [:h2
        {:class "text-3xl font-bold mb-6"}
        "Join the Founding Practitioners Circle"]
       [:p
        {:class "text-xl mb-8"}
        "Parts is being actively developed — be among the first IFS practitioners to help shape its future."]
       [:div
        {:class "rounded-lg p-6 mb-8",
         :style {:background-color "rgba(255, 255, 255, 0.1)"}}
        [:div
         {:class "grid grid-cols-1 md:grid-cols-3 gap-4"}
         [:div
          {:class "flex flex-col items-center p-4 rounded-md",
           :style {:background-color "rgba(255, 255, 255, 0.1)"}}
          [:svg
           {:xmlns "http://www.w3.org/2000/svg",
            :class "h-8 w-8 mb-2",
            :fill "none",
            :viewBox "0 0 24 24",
            :stroke "currentColor"}
           [:path
            {:stroke-linecap "round",
             :stroke-linejoin "round",
             :stroke-width "2",
             :d
             "M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"}]]
          [:span {:class "font-semibold"} "Some benefit"]]
         [:div
          {:class "flex flex-col items-center p-4 rounded-md",
           :style {:background-color "rgba(255, 255, 255, 0.1)"}}
          [:svg
           {:xmlns "http://www.w3.org/2000/svg",
            :class "h-8 w-8 mb-2",
            :fill "none",
            :viewBox "0 0 24 24",
            :stroke "currentColor"}
           [:path
            {:stroke-linecap "round",
             :stroke-linejoin "round",
             :stroke-width "2",
             :d
             "M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z"}]]
          [:span {:class "font-semibold"} "Some benefit"]]
         [:div
          {:class "flex flex-col items-center p-4 rounded-md",
           :style {:background-color "rgba(255, 255, 255, 0.1)"}}
          [:svg
           {:xmlns "http://www.w3.org/2000/svg",
            :class "h-8 w-8 mb-2",
            :fill "none",
            :viewBox "0 0 24 24",
            :stroke "currentColor"}
           [:path
            {:stroke-linecap "round",
             :stroke-linejoin "round",
             :stroke-width "2",
             :d
             "M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"}]]
          [:span {:class "font-semibold"} "Some benefit"]]]]
       [:div.mx-auto
        (partials/waitlist-signup-form)]
       [:p
        {:class "mt-3 text-sm", :style {:opacity "0.8"}}
        "No credit card required"]]]
     [:section
      {:class "py-16"}
      [:div
       {:class "container max-w-7xl mx-auto px-4 sm:px-6 lg:px-8"}
       [:h2
        {:class "text-3xl font-bold text-center mb-12"}
        "Who made this?"]
       [:div
        {:class "grid grid-cols-1 md:grid-cols-2 gap-12 max-w-4xl mx-auto"}
        [:div
         {:class "flex"}
         [:img
          {:class "w-20 h-20 rounded-full",
           :src "/images/avatars/gosha.svg"
           :alt "Gosha Tcherednitchenko"}]
         [:div
          {:class "ml-6"}
          [:h3 {:class "text-xl font-semibold"} "Gosha Tcherednitchenko"]
          [:p
           {:class "text-gray-600 mt-1"}
           "Software engineer with 20 years experience building for the Web."]
          [:div
           {:class "mt-2"}
           [:a
            {:href "#", :class "text-ifs-green hover:underline mr-3"}
            "Website"]
           [:a
            {:href "#", :class "text-ifs-green hover:underline"}
            "Bluesky"]]]]
        [:div
         {:class "flex"}
         [:img
          {:class "w-20 h-20 rounded-full",
           :src "/images/avatars/tingyi.svg"
           :alt "Ting-yi Lai"}]
         [:div
          {:class "ml-6"}
          [:h3 {:class "text-xl font-semibold"} "Ting-yi Lai"]
          [:p
           {:class "text-gray-600 mt-1"}
           "IFS Level 1 trained art psychotherapist, focusing on trauma."]
          [:div
           {:class "mt-2"}
           [:a
            {:href "#", :class "text-ifs-yellow hover:underline"}
            "Portfolio"]]]]]]]))))
