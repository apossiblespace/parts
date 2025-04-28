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
     [:section
      {:class "bg-bg-secondary py-24 sm:py-32"}
      [:div
       {:class "mx-auto max-w-7xl px-6 lg:px-8"}
       [:div
        {:class "mx-auto max-w-2xl sm:text-center"}
        [:h2
         {:class
          "text-34l font-semibold tracking-tight text-balance text-gray-900 sm:text-5xl"}
         "Meet the team"]]
       [:ul
        {:role "list",
         :class
         "mx-auto mt-20 grid max-w-2xl grid-cols-1 gap-x-6 gap-y-20 sm:grid-cols-2 lg:max-w-4xl lg:gap-x-8 xl:max-w-none"}
        [:li
         {:class "flex flex-col gap-6 xl:flex-row"}
         [:img
          {:class "w-52 flex-none rounded-full object-cover",
           :src "/images/avatars/gosha.svg"
           :alt "Gosha Tcherednitchenko"}]
         [:div
          {:class "flex-auto"}
          [:h3
           {:class "text-lg/8 font-semibold tracking-tight text-gray-900"}
           "Gosha Tcherednitchenko"]
          [:p
           {:class "mt-6 text-base/7 text-gray-600"}
           "A software engineer with 20 years of experience building for the Web"]
          [:p
           {:class "mt-6 text-base/7 text-gray-600"}
           "Online: "
           [:a {:href "https://gosha.net"} "Website"]
           ", "
           [:a {:href "https://bsky.app/profile/gosha.net"} "Bluesky"]]]]
        [:li
         {:class "flex flex-col gap-6 xl:flex-row"}
         [:img
          {:class "w-52 flex-none rounded-full object-cover",
           :src "/images/avatars/tingyi.svg"
           :alt "Ting-yi Lai"}]
         [:div
          {:class "flex-auto"}
          [:h3
           {:class "text-lg/8 font-semibold tracking-tight text-gray-900"}
           "Ting-yi Lai"]
          [:p
           {:class "mt-6 text-base/7 text-gray-600"}
           "An IFS Level 1 trained art psychotherapist, focusing on trauma."]]]]]]))))
