(ns parts.handlers.pages
  (:require
   [hiccup2.core :refer [html]]
   [parts.handlers.waitlist :refer [signups-count]]
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
  (let [waitlist-count (signups-count)]
    (response/response
     (html
      (layouts/main
       {:title "Mapping tools for IFS practitioners and their clients"
        :styles ["/css/flow.css" "/css/style.css"]}
       [:section
        [:div
         {:class ["grid" "grid-cols-1" "md:grid-cols-2"
                  "gap-12" "mx-auto" "max-w-7xl"
                  "container" "px-4" "sm:px-6" "lg:px-8"]}
         [:div
          [:h1
           {:class ["text-5xl" "md:text-6xl" "font-bold" "my-16"]}
           "Understand your clients’ parts and their relationships."]
          [:h3.my-8.text-xl
           [:strong.font-bold "Parts"]
           " is a mapping tool for IFS practitioners to keep track of, visualise, and explore the relationships between their clients' parts."]
          [:div.grid.grid-cols-1.md:grid-cols-2.gap-2.w-full
           [:a.btn.btn-primary.btn-lg.hover:bg-opacity-90.transform.hover:scale-105.transition.duration-200
            {:role "button"
             :href "#signup"}
            "Join the Founding Circle"]
           [:a.btn.btn-lg {:role "button"
                           :href "/system"}
            "Try the playground"]]
          [:p
           {:class ["my-4" "w-full" "text-gray-500" "text-sm"]}
           (str "Current founding members: " waitlist-count " practitioners.")]]
         [:div#root.demo.minimal {:data-demo-mode "minimal"}]]]
       [:section#signup.py-20.text-white
        {:style {:background-color "#4eb48a"}}
        [:div.container.max-w-3xl.mx-auto.px-4.sm:px-6.lg:px-8.text-center
         [:h2.text-3xl.font-bold.mb-6
          "Join the Founding Practitioners Circle"]
         [:p.text-xl.mb-8
          "Parts is being actively developed — be among the first IFS practitioners to help shape its future."]
         [:div.rounded-lg.p-6.mb-8
          {:style {:background-color "rgba(255, 255, 255, 0.1)"}}
          [:div.grid.grid-cols-1.md:grid-cols-3.gap-4
           [:div.flex.flex-col.items-center.p-4.rounded-md
            {:style {:background-color "rgba(255, 255, 255, 0.1)"}}
            [:svg.h-8.w-8.mb-2
             {:xmlns "http://www.w3.org/2000/svg",
              :fill "none",
              :viewBox "0 0 24 24",
              :stroke "currentColor"}
             [:path
              {:stroke-linecap "round",
               :stroke-linejoin "round",
               :stroke-width "2",
               :d
               "M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"}]]
            [:span.font-semibold "Some benefit"]]
           [:div.flex.flex-col.items-center.p-4.rounded-md
            {:style {:background-color "rgba(255, 255, 255, 0.1)"}}
            [:svg.h-8.w-8.mb-2
             {:xmlns "http://www.w3.org/2000/svg",
              :fill "none",
              :viewBox "0 0 24 24",
              :stroke "currentColor"}
             [:path
              {:stroke-linecap "round",
               :stroke-linejoin "round",
               :stroke-width "2",
               :d
               "M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z"}]]
            [:span.font-semibold "Some benefit"]]
           [:div.flex.flex-col.items-center.p-4.rounded-md
            {:style {:background-color "rgba(255, 255, 255, 0.1)"}}
            [:svg.h-8.w-8.mb-2
             {:xmlns "http://www.w3.org/2000/svg",
              :fill "none",
              :viewBox "0 0 24 24",
              :stroke "currentColor"}
             [:path
              {:stroke-linecap "round",
               :stroke-linejoin "round",
               :stroke-width "2",
               :d
               "M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"}]]
            [:span.font-semibold "Some benefit"]]]]
         [:div.mx-auto
          (partials/waitlist-signup-form)]
         [:p.mt-3.text-sm
          {:style {:opacity "0.8"}}
          "No credit card required"]]]
       [:section.py-16
        [:div.container.max-w-7xl.mx-auto.px-4.sm:px-6.lg:px-8
         [:h2.text-3xl.font-bold.text-center.mb-12
          "Who made this?"]
         [:div.grid.grid-cols-1.md:grid-cols-2.gap-12.max-w-4xl.mx-auto
          [:div.flex
           [:img.w-20.h-20.rounded-full
            {:src "/images/avatars/gosha.svg"
             :alt "Gosha Tcherednitchenko"}]
           [:div.ml-6
            [:h3.text-xl.font-semibold "Gosha Tcherednitchenko"]
            [:p.text-gray-600.mt-1
             "Software engineer with 20 years experience building for the Web."]
            [:div.mt-2
             [:a.text-ifs-green.hover:underline.mr-3
              {:href "#"}
              "Website"]
             [:a.text-ifs-green.hover:underline
              {:href "#"}
              "Bluesky"]]]]
          [:div.flex
           [:img.w-20.h-20.rounded-full
            {:src "/images/avatars/tingyi.svg"
             :alt "Ting-yi Lai"}]
           [:div.ml-6
            [:h3.text-xl.font-semibold "Ting-yi Lai"]
            [:p.text-gray-600.mt-1
             "IFS Level 1 trained art psychotherapist, focusing on trauma."]
            [:div.mt-2
             [:a.text-ifs-yellow.hover:underline
              {:href "#"}
              "Portfolio"]]]]]]])))))
