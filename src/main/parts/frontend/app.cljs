(ns parts.frontend.app
  (:require
   ["htmx.org" :default htmx]
   [parts.frontend.components.system :refer [system]]
   [uix.core :refer [defui $]]
   [uix.dom]))

(def system-data
  {:nodes
   [{:id "1" :position {:x 300 :y 130} :type "manager" :data {:label "Manager"}}
    {:id "2" :position {:x 200 :y 300} :type "exile" :data {:label "Exile"}}
    {:id "3" :position {:x 100 :y 130} :type "firefighter" :data {:label "Firefighter"}}]
   :edges
   [{:id "e1-2" :source "1" :target "2"}
    {:id "e3-2" :source "3" :target "2"}]})

(defui app []
  ($ system system-data))

(defonce root
  (when-let [root-element (js/document.getElementById "root")]
    (uix.dom/create-root root-element)))

(defn track-source-attribution []
  (when (and js/document.referrer js/window.plausible)
    (let [params (js/URLSearchParams. js/window.location.search)]
      (js/window.plausible
       "Source"
       #js{:props #js{:referrer js/document.referrer
                      :campaign (.get params "utm_campaign")
                      :source (.get params "utm_source")
                      :medium (.get params "utm_medium")}}))))

(defn setup-form-tracking []
  (when js/window.plausible
    (let [emailField (js/document.getElementById "email")]
      (when emailField
        (.addEventListener
         emailField "focus"
         #(js/window.plausible
           "EmailFocus"
           #js{:props #js{:field "email"}}))))

    (let [form (js/document.querySelector "#signup-form form")]
      (when form
        (.addEventListener
         form "submit"
         #(js/window.plausible
           "FormSubmission"
           #js{:props #js{:form "waitlist-signup"}}))))))

(defn render-app []
  "Render the app if root element exists"
  (when root
    (uix.dom/render-root ($ app) root)))

(defn ^:export init []
  (.on htmx "htmx:load"
       (fn [_]
         (render-app)
         (let [version (.-version htmx)]
           (js/console.log "HTMX loaded! Version:" version))
         (track-source-attribution)
         (setup-form-tracking))))

(defn ^:dev/after-load reload! []
  (js/console.log "Reloading app...")
  (uix.dom/render-root ($ app) root))
