(ns aps.parts.views.layouts
  (:require
   [aps.parts.views.partials :as partials]
   [hiccup2.core :refer [html]]))

(def ^:private default-options
  {:description "Parts is a mapping tool for IFS practitioners to keep track of, visualise, and explore the relationships between their clients’ parts."})

(defn- page
  "Head + body shell + scripts. The body content is whatever the caller
   passes, including any header/footer chrome the specific layout adds."
  [options & content]
  (let [options (merge default-options options)]
    (html
     (partials/head options)
     [:body.font-sans.bg-gray-50.text-gray-900
      content
      (partials/scripts options)])))

(defn marketing
  "Marketing/landing layout: full site header and footer."
  [options & content]
  (page (assoc options :analytics? true)
        (partials/header)
        content
        (partials/footer)))

(defn document
  "Document-reading layout for the legal pages: a compact header (logo + legal
   nav) and a compact footer. Auth-agnostic. `:active` in options is the slug
   of the current document, highlighted in the nav."
  [options & content]
  (page (assoc options :analytics? true)
        (partials/document-header (:active options))
        [:main {:class "container max-w-3xl mx-auto px-4 py-12"} content]
        (partials/document-footer)))

(defn fullscreen
  "Full-screen layout with no chrome (the SPA shell, invite pages)."
  [options & content]
  (page options content))
