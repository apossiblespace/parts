(ns aps.parts.views.partials
  (:require
   [aps.parts.common.constants :as c]
   [aps.parts.config :as conf]
   [aps.parts.launch :as launch]
   [aps.parts.version :as version]
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
  ([{:keys [title description styles]}]
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:meta {:name "description" :content description}]
    [:meta {:name "theme-color" :content "#62a294"}]
    [:meta {:name "csrf-token" :content *anti-forgery-token*}]
    [:meta {:name "version" :content (version/current)}]
    [:link {:rel "icon" :sizes "192x192" :href "/images/icons/favicon.png"}]
    [:link {:rel "apple-touch-icon" :href "/images/icons/favicon.png"}]
    [:title (if title
              (str title " – " c/brand-suffix)
              c/brand-suffix)]
    ;; [:link {:rel "stylesheet" :href "/css/style.css"}]
    (for [href (or styles [])]
      [:link {:rel "stylesheet" :href href}])
    [:script {:defer       true
              :data-domain (conf/app-domain)
              :src         "https://plausible.io/js/script.outbound-links.tagged-events.js"}]
    [:script "window.plausible = window.plausible || function() { (window.plausible.q = window.plausible.q || []).push(arguments) }"]]))

(defn header-signup
  "Post-launch site header: Log in + Create an account buttons."
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
             :src   "/images/parts-logo-horizontal.svg"}]]
     [:div {:class "flex items-center space-x-4"}
      [:a
       {:class "text-gray-600 hover:text-gray-900 font-medium"
        :href  "/app/login"}
       "Log in"]
      [:a
       {:class   "btn btn-primary"
        :href    "/app/signup"
        :onclick "plausible('Create Account Click', {props: {source: 'homepage-header'}}); return true;"}
       "Create an account"]]]]])

(defn header-waitlist
  "Pre-launch site header: a jump to the founding-circle form, plus a
   Log in link for already-onboarded Circle members."
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
             :src   "/images/parts-logo-horizontal.svg"}]]
     [:div {:class "flex items-center space-x-4"}
      [:a
       {:href    "#signup",
        :class   "text-ifs-green font-semibold hover:underline"
        :onclick "plausible('Join Founding Circle Click', {props: {source: 'homepage'}}); return true;"}
       "Join Founding Circle"]
      [:a
       {:href    "/app/login"
        :class   "btn btn-soft"
        :onclick "plausible('Login Click', {props: {source: 'homepage'}}); return true;"}
       "Log in"]]]]])

(defn header
  "Site header. Picks the signup or waitlist variant based on the runtime
   launch toggle (see `aps.parts.launch`)."
  []
  (if (launch/launched?)
    (header-signup)
    (header-waitlist)))

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
       [:img {:class "h-6"
              :src   "/images/parts-logo-mini.svg"}]]
      [:p
       {:class "mt-4 text-gray-600"}
       "Open-source IFS part mapping solution"]]
     [:div
      [:h3 {:class "font-semibold text-gray-900 mb-4"} "Quick Links"]
      [:ul
       {:class "space-y-2"}
       [:li
        [:div
         {:class "tooltip tooltip-right cursor-not-allowed" :data-tip "Coming soon!"}
         [:span
          {:class "underline underline-offset-4 text-gray-600 hover:text-ifs-green"}
          "Features"]]]
       [:li
        [:div
         {:class "tooltip tooltip-right cursor-not-allowed" :data-tip "Coming soon!"}
         [:span
          {:class "underline underline-offset-4 text-gray-600 hover:text-ifs-green"}
          "Pricing"]]]
       [:li
        [:a
         {:href "https://github.com/apossiblespace/parts?tab=readme-ov-file#readme", :class "text-gray-600 hover:text-ifs-green"}
         "Documentation"]]
       [:li
        [:a
         {:href "https://github.com/apossiblespace/parts", :class "text-gray-600 hover:text-ifs-green"}
         "Source code on GitHub"]]]]
     [:div
      [:h3 {:class "font-semibold text-gray-900 mb-4"} "Legal"]
      [:ul
       {:class "space-y-2"}
       [:li
        [:div
         {:class "tooltip tooltip-right cursor-not-allowed" :data-tip "Coming soon!"}
         [:span
          {:class "underline underline-offset-4 text-gray-600 hover:text-ifs-green"}
          "Privacy Policy"]]]
       [:li
        [:div
         {:class "tooltip tooltip-right cursor-not-allowed" :data-tip "Coming soon!"}
         [:span
          {:class "underline underline-offset-4 text-gray-600 hover:text-ifs-green"}
          "Terms of Service"]]]]]]
    [:div
     {:class
      "mt-12 pt-8 border-t border-gray-200 text-sm text-gray-500 flex justify-between items-center"}
     [:p
      "© 2026 "
      [:a {:href "https://a.possible.space"} "A Possible Space Ltd."]
      ", company number 11617016."
      [:span {:class "text-gray-400 ml-1"} "Made with ❤️ in London, U.K."]]
     [:p
      [:span {:class "badge badge-xs text-gray-300 font-mono"} (version/current)]]]]])

(defn waitlist-signup-form
  "Form for signing up for the waiting list"
  [{:keys [message value]}]
  [:div#signup-form
   [:form
    {:hx-post      "/waitlist-signup"
     :hx-target    "#signup-form"
     :hx-swap      "outerHTML"
     :hx-on:submit "plausible('Waitlist Signup', {props: {source: 'homepage'}}); return true;"}
    [:div.join.rounded-xl
     [:input.join-item.input.input-xl.text-gray-800
      {:type        "email"
       :id          "email"
       :name        "email"
       :placeholder "self@you.com"
       :value       value
       :hx-on:focus "plausible('Email Field Focus', {props: {source: 'homepage'}}); return true;"}]
     [:input {:type  "hidden"
              :id    "__anti-forgery-token"
              :name  "__anti-forgery-token"
              :value *anti-forgery-token*}]
     [:input.join-item.btn.btn-xl.btn-primary
      {:type "submit" :value "Sign me up!"}]]]
   [:div.relative.mt-1.ml-6.inline-block
    [:p.relative.mt-3.text-sm
     {:style {:opacity "0.8"}}
     (if message
       [:span
        {:class "before:content-['⤴︎'] before:absolute before:-left-5 before:-top-1 before:scale-x-[-1]"}
        message]
       [:span
        "No credit card required."])]]])

(defn- invite-card
  "Shared centered-card chrome for the server-rendered invite pages: logo
   above a white card wrapping the given `body` elements."
  [& body]
  [:div {:class "min-h-screen flex items-center justify-center bg-gray-50 px-4 py-12"}
   [:div {:class "w-full max-w-md"}
    [:a {:href "/" :class "flex justify-center mb-6"}
     [:img {:class "w-44" :src "/images/parts-logo-horizontal.svg"}]]
    [:div {:class "card bg-white shadow-sm"}
     [:div {:class "card-body"} body]]]])

(defn invite-signup-content
  "The Founding Circle signup page body, rendered for a valid invitation
   token. Email is pre-filled and read-only (it comes from the invitation,
   not the form); on a validation error, `error` is shown and the typed
   `values` (a form-params map with string keys) are kept."
  [{:keys [token email error values]}]
  (invite-card
   [:h1 {:class "text-2xl font-bold mb-1"}
    "You’re invited!"]
   [:p {:class "text-gray-600 mb-6"}
    "Create your account to start using Parts."]
   (when error
     [:div {:class "alert alert-error text-sm mb-4"} error])
   [:form {:method "post" :action (str "/invite/" token)}
    [:input {:type "hidden" :name "__anti-forgery-token" :value *anti-forgery-token*}]
    ;; Display-only: the account's email is fixed by the invitation token,
    ;; never taken from this form. No `name`, so it is not submitted at all
    ;; — there is no attacker-controllable email in the request.
    [:label {:class "fieldset-label"} "Email"]
    [:input {:class    "input input-bordered w-full mb-3"
             :type     "email"
             :value    email
             :disabled true}]
    [:label {:class "fieldset-label"} "Display name"]
    [:input {:class       "input input-bordered w-full mb-3"
             :type        "text"
             :name        "display_name"
             :value       (get values "display_name" "")
             :placeholder "How your name appears in Parts"
             :required    true}]
    [:label {:class "fieldset-label"} "Username"]
    [:input {:class    "input input-bordered w-full mb-3"
             :type     "text"
             :name     "username"
             :value    (get values "username" "")
             :required true}]
    [:label {:class "fieldset-label"} "Password"]
    [:input {:class    "input input-bordered w-full mb-3"
             :type     "password"
             :name     "password"
             :required true}]
    [:label {:class "fieldset-label"} "Confirm password"]
    [:input {:class    "input input-bordered w-full mb-4"
             :type     "password"
             :name     "password_confirmation"
             :required true}]
    [:button {:class "btn btn-primary w-full" :type "submit"}
     "Create my account"]]))

(defn invite-unavailable-content
  "The calm error page body, shown for any unusable invitation token —
   unknown, already redeemed, or revoked. Deliberately one message for all
   three: it does not reveal which failure mode occurred."
  []
  (invite-card
   [:h1 {:class "text-2xl font-bold mb-2"} "This invite link isn’t available"]
   [:p {:class "text-gray-600"}
    "This invitation link is no longer valid — it may already have been used.
     If you think this is a mistake, please get in touch with the person who
     invited you."]))
