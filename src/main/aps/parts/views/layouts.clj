(ns aps.parts.views.layouts
  (:require
   [aps.parts.views.partials :as partials]
   [hiccup2.core :refer [html raw]]))

(def ^:private default-options
  {:description "Parts is a mapping tool for IFS practitioners to keep track of, visualise, and explore the relationships between their clients’ parts."})

(defn- page
  "Head + body shell + scripts. The body content is whatever the caller
   passes, including any header/footer chrome the specific layout adds."
  [options & content]
  (let [options (merge default-options options)]
    (html
     (raw "<!DOCTYPE html>")
     [:html {:lang "en" :class (:html-class options)}
      (partials/head options)
      [:body.font-sans.bg-gray-50.text-gray-900
       content
       (partials/scripts options)]])))

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
  "Full-screen layout with no chrome (the SPA shell, invite pages).
   Tags the html element as the app shell so app-only adaptations
   (touch-device scaling, tooltip suppression, edge-to-edge viewport)
   can target it without leaking into other layouts. Callers that are
   content pages rather than the canvas app — the invite pages — pass
   `:html-class nil` to opt out."
  [options & content]
  (page (merge {:html-class "app"} options) content))

(defn content-page
  "A server-rendered content page in the fullscreen shell — the invite and
   password-reset pages. Opts out of the app-shell adaptations (these are
   content pages, not the canvas app) and carries the app stylesheet.
   Returns the complete rendered document."
  [title & content]
  (fullscreen {:title      title
               :styles     ["/css/style.css"]
               :html-class nil}
              content))
