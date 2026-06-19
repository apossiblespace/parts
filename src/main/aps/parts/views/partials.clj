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
   :styles      - additional stylesheets
   :analytics?  - load the Plausible collector. Public marketing pages only —
                  never the signed-in app or invite pages, where the URL would
                  carry a Map id or invite token."
  ([] (head {}))
  ([{:keys [title description styles analytics?]}]
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
    (when analytics?
      (list
       [:script {:defer       true
                 :data-domain (conf/app-domain)
                 :src         "https://plausible.io/js/script.outbound-links.tagged-events.js"}]
       [:script "window.plausible = window.plausible || function() { (window.plausible.q = window.plausible.q || []).push(arguments) }"]))]))

(defn header-signup
  "Post-launch site header: Log in + Create an account buttons."
  []
  [:header
   ;; Overlays the hero so the demo canvas shows through behind it.
   {:class "absolute top-0 left-0 right-0 z-20 py-6"}
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
   ;; Overlays the hero so the demo canvas shows through behind it.
   {:class "absolute top-0 left-0 right-0 z-20 py-6"}
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
         "Source code on GitHub"]]
       [:li
        [:a
         {:href (str "mailto:" c/support-email), :class "text-gray-600 hover:text-ifs-green"}
         "Contact"]]]]
     [:div
      [:h3 {:class "font-semibold text-gray-900 mb-4"} "Legal"]
      [:ul
       {:class "space-y-2"}
       (for [{:keys [slug label]} c/legal-documents]
         [:li
          [:a {:href (str "/" slug) :class "text-gray-600 hover:text-ifs-green"}
           label]])]]]
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

(defn document-header
  "Compact header for the document layout: the Parts logo (links home) on the
   left, and the legal-document nav on the right with `active` (a slug)
   highlighted."
  [active]
  [:header {:class "border-b border-gray-200 bg-white"}
   [:div {:class "container max-w-3xl mx-auto px-4 py-4 flex justify-between items-center"}
    [:a {:href "/" :class "flex items-center"}
     [:img {:class "h-7" :src "/images/parts-logo-horizontal.svg"}]]
    [:nav {:class "flex items-center gap-4"}
     (for [{:keys [slug label]} c/legal-documents]
       [:a {:href  (str "/" slug)
            :class (if (= slug active)
                     "text-sm text-ifs-green font-semibold"
                     "text-sm text-gray-600 hover:text-ifs-green")}
        label])]]])

(defn document-footer
  "Compact footer for the document layout: copyright and build version."
  []
  [:footer {:class "border-t border-gray-200 mt-12"}
   [:div {:class "container max-w-3xl mx-auto px-4 py-6 flex justify-between items-center text-sm text-gray-500"}
    [:p "© 2026 "
     [:a {:href "https://a.possible.space"} "A Possible Space Ltd."]]
    [:span {:class "badge badge-xs text-gray-300 font-mono"} (version/current)]]])

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
   [:form {:id "invite-form" :method "post" :action (str "/invite/" token)}
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
    [:label {:class "flex items-start gap-3 mb-2 cursor-pointer"}
     [:input {:type     "checkbox"
              :name     "accept_medical"
              :class    "checkbox checkbox-sm shrink-0 mt-0.5"
              :required true}]
     [:span {:class "text-sm text-left"}
      c/medical-data-notice]]
    [:label {:class "flex items-start gap-3 mb-4 cursor-pointer"}
     [:input {:type     "checkbox"
              :name     "accept_legal"
              :class    "checkbox checkbox-sm shrink-0 mt-0.5"
              :required true}]
     [:span {:class "text-sm text-left"}
      "I have read and agree to the "
      (interpose ", "
                 (for [{:keys [slug label]} c/legal-documents]
                   [:a {:href   (str "/" slug)
                        :target "_blank"}
                    label]))
      "."]]
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
     If you think this is a mistake, please reach out to us at "
    [:a {:href  (str "mailto:" c/support-email)
         :class "underline underline-offset-4 hover:text-ifs-green"}
     c/support-email]
    " and we’ll sort it out."]))
