(ns parts.frontend.app
  ;; TODO: We probably need to split the nodes UI into a separate component and
  ;; only load this when the user is signed in. It doesn't make sense to always
  ;; be loading this.
  (:require
   ["htmx.org" :default htmx]
   ["reactflow" :refer [ReactFlow
                        MiniMap
                        Controls
                        Background
                        useNodesState
                        useEdgesState
                        addEdge]]
   [uix.core :refer [defui $]]
   [uix.dom]))

;; NOTE: Layouting
;; https://reactflow.dev/learn/layouting/layouting
;; https://d3js.org/d3-force
;; https://marvl.infotech.monash.edu/webcola/

(def initial-nodes
  [{:id "1" :position {:x 0 :y 0} :data {:label "1"}}
   {:id "2" :position {:x 0 :y 100} :data {:label "2"}}])

(def initial-edges
  [{:id "e1-2" :source "1" :target "2"}])

(defui app []
  (let [[nodes setNodes onNodesChange] (useNodesState (clj->js initial-nodes))
        [edges setEdges onEdgesChange] (useEdgesState (clj->js initial-edges))
        on-connect (uix.core/use-callback
                    (fn [params]
                      (setEdges (fn [eds] (addEdge params eds))))
                    [setEdges])]
    ($ :div {:style {:width "100vw" :height "100vh"}}
       ($ ReactFlow {:nodes nodes
                     :edges edges
                     :onNodesChange onNodesChange
                     :onEdgesChange onEdgesChange
                     :onConnect on-connect}
          ($ MiniMap)
          ($ Controls)
          ($ Background {:variant "dots"
                         :gap 12
                         :size 1})))))

(defonce root
  (uix.dom/create-root (js/document.getElementById "root")))

(defn ^:export init []
  (.on htmx "htmx:load"
       (fn [_]
         (uix.dom/render-root ($ app) root)
         (let [version (.-version htmx)]
           (js/console.log "HTMX loaded! Version:" version)))))
