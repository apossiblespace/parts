(ns aps.parts.handlers.pages
  (:require
   [aps.parts.handlers.waitlist :refer [signups-count]]
   [aps.parts.views.layouts :as layouts]
   [aps.parts.views.partials :as partials]
   [hiccup2.core :refer [html]]
   [ring.util.response :as response]))

(defn system-graph
  "Page rendering the graph of a system"
  [{:keys [demo-mode]}]
  (let [demo-mode (or demo-mode false)]
    (response/response
     (html
      (layouts/fullscreen
       {:title  "System"
        :styles ["/css/flow.css" "/css/style.css"]}
       [:div#root {:data-demo-mode demo-mode}])))))

(defn playground
  "Page rendering a playground system graph in demo mode"
  [_]
  (system-graph {:demo-mode "true"}))

(defn home-page
  "Page rendered for GET /"
  [_]
  (let [waitlist-count (signups-count)]
    (response/response
     (html
      (layouts/main
       {:title  "Mapping tools for IFS practitioners and their clients"
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
           " is a mapping tool for IFS practitioners to keep track of, visualise, and explore the relationships between their clients' aps.parts."]
          [:div.grid.grid-cols-1.md:grid-cols-2.gap-2.w-full
           [:a.btn.btn-primary.btn-lg.hover:bg-opacity-90.transform.hover:scale-105.transition.duration-200
            {:role "button"
             :href "#signup"}
            "Join the Founding Circle"]
           [:a.btn.btn-lg {:role "button"
                           :href "/playground"}
            "Try the playground"]]
          [:p
           {:class ["my-4" "w-full" "text-gray-500" "text-sm"]}
           [:span "Current founding members: "]
           [:span#counter waitlist-count]
           [:span " practitioners."]]]
         [:div#root
          {:data-demo-mode "minimal"
           :class          ["demo" "minimal" "bg-white" "mb-4" "rounded-lg" "shadow-sm"]}]]]
       [:section#signup.py-20.text-white
        {:style {:background-color "#4eb48a"}}
        [:div.container.max-w-7xl.mx-auto.px-4.sm:px-6.lg:px-8
         [:h2.text-3xl.font-bold.mb-6.text-center
          "Join the Founding Practitioners Circle"]
         [:p.text-xl.mb-8.text-center
          "Parts is being actively developed — be among the first IFS practitioners to help shape its future."]
         [:div.grid.grid-cols-1.md:grid-cols-3.gap-4.md:gap-8.max-w-5xl.mx-auto.my-12
          [:div.flex
           [:img.w-20.h-20
            {:src "/images/icons/build.png"
             :alt "An icon representing a toolbox with some tools in it"}]
           [:div.ml-4
            [:h3.text-lg.font-bold "Help shape Parts"]
            [:p
             "Your feedback will make Parts better for clients & therapists"]]]
          [:div.flex
           [:img.w-20.h-20
            {:src "/images/icons/key.png"
             :alt "An icon representing a key on a keychain"}]
           [:div.ml-4
            [:h3.text-lg.font-bold "Early access"]
            [:p
             "Start using Parts and new features before general availability"]]]
          [:div.flex
           [:img.w-20.h-20
            {:src "/images/icons/concierge.png"
             :alt "An icon representing a concierge's bell"}]
           [:div.ml-4
            [:h3.text-lg.font-bold "Concierge support"]
            [:p
             "Help getting setup and started, straight from the developer"]]]]
         [:div.mx-auto.text-center
          (partials/waitlist-signup-form {})]]]
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
             [:a.text-ifs-green.mr-3
              {:href "https://gosha.net"}
              "Website"]
             [:a.text-ifs-green
              {:href "https://bsky.app/profile/gosha.net"}
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
             [:a.text-ifs-yellow
              {:href "https://tingyilai.com"}
              "Website"]]]]]]])))))
