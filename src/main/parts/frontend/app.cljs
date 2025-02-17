(ns parts.frontend.app
  (:require
   ["htmx.org" :default htmx]
   [parts.frontend.graph :as graph]))

(defonce cy-instance (atom nil))

;; (def system-data
;;   {:nodes
;;    [{:id "1" :position {:x 300 :y 130} :type "manager" :data {:label "Manager"}}
;;     {:id "2" :position {:x 200 :y 300} :type "exile" :data {:label "Exile"}}
;;     {:id "3" :position {:x 100 :y 130} :type "firefighter" :data {:label "Firefighter"}}]
;;    :edges
;;    [{:id "e1-2" :source "1" :target "2"}
;;     {:id "e3-2" :source "3" :target "2"}]})

;; (defui app []
;;   ($ system system-data))

;; (defonce root
;;   (uix.dom/create-root (js/document.getElementById "root")))

(def system-data
  [{:group "nodes"
    :data {:id "1" :label "Manager"}
    :position {:x 300 :y 130}
    :classes "manager"}
   {:group "nodes"
    :data {:id "2" :label "Exile"}
    :position {:x 200 :y 300}
    :classes "exile"}
   {:group "nodes"
    :data {:id "3" :label "Firefighter"}
    :position {:x 100 :y 130}
    :classes "firefighter"}
   {:group "edges"
    :data {:id "e1-2" :source "1" :target "2"}}
   {:group "edges"
    :data {:id "e3-2" :source "3" :target "2"}}])

(defn ^:export init []
  (.on htmx "htmx:load"
       (fn [_]
         ;; (uix.dom/render-root ($ app) root)
         (reset! cy-instance (graph/init system-data))
         (let [version (.-version htmx)]
           (js/console.log "HTMX loaded! Version:" version)))))

(defn ^:dev/after-load reload! []
  (js/console.log "Reloading app...")
  (reset! cy-instance (graph/init system-data)))

  ;; (uix.dom/render-root ($ app) root))
