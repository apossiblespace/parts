(ns apossiblespace.parts.layouts.main
  (:require [hiccup.page :refer [html5 include-css include-js]]))

(defn layout [title & content]
  (html5
   [:head
    [:title title]
    (include-css "/css/style.css")
    (include-js "/js/main.js")]
   [:body
    [:div#app content]
    [:script
     "window.addEventListener('load', function () { apossiblespace.parts.core.init(); });"]]))
