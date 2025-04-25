(ns parts.views.partials
  (:require
   [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]))

(defn scripts
  "Render script tags at the bottom of the main body tag.
   Always includes main.js plus any additional scripts from options."
  [{:keys [scripts]}]
  (for [src (into ["/js/main.js"] (or scripts []))]
    [:script {:src src}]))

(def default-title
  "Parts: IFS parts mapping for therapists & clients")

(defn head
  "Head tag with configurable options.
   Options map can include:
   :title       - page title
   :description - meta description
   :styles      - additional stylesheets"
  ([] (head {}))
  ([{:keys [title description styles]}]
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:meta {:name "description" :content description}]
    [:meta {:name "theme-color" :content "#62a294"}]
    [:meta {:name "csrf-token" :content *anti-forgery-token*}]
    [:link {:rel "icon" :sizes "192x192" :href "/images/icons/favicon.png"}]
    [:link {:rel "apple-touch-icon" :href "/images/icons/favicon.png"}]
    [:title (if title
              (str title " — " default-title)
              default-title)]
    ;; [:link {:rel "stylesheet" :href "/css/style.css"}]
    [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
    [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin true}]
    [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=DM+Serif+Display:ital@0;1&display=swap"}]
    (for [href (or styles [])]
      [:link {:rel "stylesheet" :href href}])
    [:script {:defer       true
              :data-domain "parts.ifs.tools"
              :src         "https://plausible.io/js/script.outbound-links.tagged-events.js"}]
    [:script "window.plausible = window.plausible || function() { (window.plausible.q = window.plausible.q || []).push(arguments) }"]]))

(defn header
  "Site header"
  []
  [:header
   {:class "py-6"}
   [:div
    {:class "container max-w-7xl mx-auto px-4 sm:px-6 lg:px-8"}
    [:div
     {:class "flex justify-between items-center"}
     [:a
      {:href "/", :class "flex items-center"}
      [:img {:class "w-50"
             :src "/images/parts-logo-horizontal.svg"}]
      [:a
       {:href "#founding-circle",
        :class "text-ifs-green font-semibold hover:underline"}
       "Join Founding Circle"]]]]])

(defn footer
  "Site footer"
  []
  [:footer
   {:class "bg-gray-100 py-12"}
   [:div
    {:class "container max-w-7xl mx-auto px-4 sm:px-6 lg:px-8"}
    [:div
     {:class "grid grid-cols-1 md:grid-cols-3 gap-8"}
     [:div
      [:div
       {:class "flex items-center"}
       [:img {:class "w-50"
              :src "/images/parts-logo-horizontal.svg"}]]
      [:p
       {:class "mt-4 text-gray-600"}
       "Parts is free, open source software. See the "
       [:a
        {:href "https://github.com/apossiblespace/parts", :class "text-ifs-green"}
        "source code on GitHub"]
       "."]]
     [:div
      [:h3 {:class "font-semibold text-gray-900 mb-4"} "Quick Links"]
      [:ul
       {:class "space-y-2"}
       [:li
        [:a
         {:href "#", :class "text-gray-600 hover:text-ifs-green"}
         "Features"]]
       [:li
        [:a
         {:href "#", :class "text-gray-600 hover:text-ifs-green"}
         "Pricing"]]
       [:li
        [:a
         {:href "#", :class "text-gray-600 hover:text-ifs-green"}
         "Documentation"]]
       [:li
        [:a
         {:href "https://github.com/apossiblespace/parts", :class "text-gray-600 hover:text-ifs-green"}
         "GitHub"]]]]
     [:div
      [:h3 {:class "font-semibold text-gray-900 mb-4"} "Legal"]
      [:ul
       {:class "space-y-2"}
       [:li
        [:a
         {:href "#", :class "text-gray-600 hover:text-ifs-green"}
         "Privacy Policy"]]
       [:li
        [:a
         {:href "#", :class "text-gray-600 hover:text-ifs-green"}
         "Terms of Service"]]]]]
    [:div
     {:class
      "mt-12 pt-8 border-t border-gray-200 text-center text-sm text-gray-500"}
     [:p
      "© 2025 "
      [:a {:href "https://a.possible.space"} "A Possible Space Ltd"]
      [:br]
      "Company number 11617016"]
     [:p {:class "mt-4"} "Made with ❤️ in London, U.K."]]]])

(defn waitlist-signup-form
  "Form for signing up for the waiting list"
  ([]
   (waitlist-signup-form nil))
  ([message]
   [:div#signup-form.w-full
    [:form.flex.flex-col.sm:flex-row.gap-2
     {:hx-post "/waitlist-signup"
      :hx-target "#signup-form"
      :hx-swap "outerHTML"
      :hx-on:submit "plausible('Waitlist Signup'); return true;"}
     [:div.join
      [:input.join-item.input
       {:type "email"
        :id "email"
        :name "email"
        :placeholder "self@you.com"
        :hx-on:focus "plausible('Email Field Focus'); return true;"}]
      [:input {:type "hidden"
               :id "__anti-forgery-token"
               :name "__anti-forgery-token"
               :value *anti-forgery-token*}]
      [:input.join-item.btn.btn-primary
       {:type "submit" :value "Sign me up!"}]]]
    (when message
      [:div.relative.mt-1.ml-6
       [:p.relative
        {:class "before:content-['⤴︎'] before:absolute before:-left-4 before:-top-1 before:scale-x-[-1]"}
        message]])
    (when (and message (re-find #"^Thank you" message))
      [:div.bg-callout.p-4.rounded-2xl.text-center.mt-2
       message])]))
