(ns parts.views.partials
  (:require
   [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]))

(defn scripts
  "Render script tags at the bottom of the main body tag.
   Always includes main.js plus any additional scripts from options."
  [{:keys [scripts]}]
  (for [src (into ["/js/main.js"] (or scripts []))]
    [:script {:src src}]))

(defn head
  "Head tag with configurable options.
   Options map can include:
   :title       - page title
   :description - meta description
   :styles      - additional stylesheets"
  ([] (head {}))
  ([{:keys [title description styles]
     :or   {description "Parts is a mapping tool for IFS practitioners to keep track of, visualise, and explore the relationships between their clients’ parts."}}]
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:meta {:name "description" :content description}]
     [:meta {:name "theme-color" :content "#62a294"}]
     [:link {:rel "icon" :sizes "192x192" :href "/images/icons/favicon.png"}]
     [:link {:rel "apple-touch-icon" :href "/images/icons/favicon.png"}]
     [:title (if title
               (str title " — Parts")
               "Parts")]
     [:link {:rel "stylesheet" :href "/css/style.css"}]
     [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
     [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin true}]
     [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=DM+Serif+Display:ital@0;1&display=swap"}]
     (for [href (or styles [])]
       [:link {:rel "stylesheet" :href href}])
     [:script {:defer       true
               :data-domain "parts.ifs.tools"
               :src         "https://plausible.io/js/script.outbound-links.tagged-events.js"}]]))

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
      "© 2025 "
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
