(ns parts.frontend.components.system
  (:require
   ["reactflow" :refer [ReactFlow
                        MiniMap
                        Controls
                        Background
                        useNodesState
                        useEdgesState
                        Panel
                        addEdge]]
   [uix.core :refer [defui $ use-state]]
   [clojure.string :as str]
   [parts.frontend.components.nodes :refer [node-types]]
   [parts.frontend.components.toolbar :refer [parts-toolbar]]
   [parts.frontend.components.login-modal :refer [login-modal]]
   [parts.frontend.utils.node-utils :refer [build-updated-part]]
   [parts.frontend.context :as ctx]))

;; FIXME: This shouldn't be returning a javascript object.
;; Ideally, we would not be manipulating JS outside of the React component at
;; all -- it should all be Clojure.
(defn- new-node [type _opts]
  #js{:id (str (random-uuid))
      :type type
      :position #js{:x 390 :y 290}
      :data #js{:label (str/capitalize type)}})

(defn- add-node
  ([type]
   (add-node type {}))
  ([type opts]
   (fn [current-nodes]
     (.concat current-nodes
              #js[(new-node type opts)]))))

;; NOTE: Layouting
;; https://reactflow.dev/learn/layouting/layouting
;; https://d3js.org/d3-force
;; https://marvl.infotech.monash.edu/webcola/

(defn- update-node-callback [setNodes id form-data]
  (setNodes
   (fn [nodes]
     (clj->js
      (map (fn [node]
             (let [node-map (js->clj node :keywordize-keys true)]
               (if (= (:id node-map) id)
                 (build-updated-part node-map form-data)
                 node-map)))
           (js->clj nodes :keywordize-keys true))))))

(defn- on-connect-callback [setEdges params]
  (setEdges #(addEdge params %)))

(defui auth-status-bar []
  (let [[show-login-modal set-show-login-modal] (use-state false)
        {:keys [user loading logout]} (ctx/use-auth)]
    ($ :div
       (when show-login-modal
         ($ login-modal
            {:show true
             :on-close #(set-show-login-modal false)}))
       (when loading
         ($ :span "(...) "))
       (if user
         ($ :span
            ($ :span {:class "user-email"} (str "🟢 " (:username user) " "))
            ($ :button {:class "btn btn-xs" :on-click (fn [] (logout))}
               "Log out"))
         ($ :span
            ($ :span "🔴")
            ($ :button {:class "btn btn-xs" :on-click #(set-show-login-modal true)}
               "Log in"))))))

(defui system [{:keys [nodes edges]}]
  (let [node-types (uix.core/use-memo (fn [] (clj->js node-types)) [node-types])
        [nodes setNodes onNodesChange] (useNodesState (clj->js nodes))
        [edges setEdges onEdgesChange] (useEdgesState (clj->js edges))
        update-node (uix.core/use-callback
                     #(update-node-callback setNodes %1 %2)
                     [setNodes])
        on-connect (uix.core/use-callback
                    #(on-connect-callback setEdges %)
                    [setEdges])]

    ($ :div {:style {:width "100vw" :height "100vh"} :class "system-view"}
       ($ (.-Provider ctx/update-node-context) {:value update-node}
          ($ ReactFlow {:nodes nodes
                        :edges edges
                        :onNodesChange onNodesChange
                        :onEdgesChange onEdgesChange
                        :onConnect on-connect
                        :nodeTypes node-types}
             ($ MiniMap)
             ($ Controls)
             ($ Panel {:position "top-left" :class "logo"}
                ($ :img {:src "/images/parts-logo-horizontal.svg" :width 150}))
             ($ Panel {:position "top-right" :class "toolbar"}
                ($ parts-toolbar
                   ($ auth-status-bar)
                   ($ :span " Add part: ")
                   ($ :div {:class "join"}
                      ($ :button
                         {:class "btn btn-xs join-item"
                          :on-click (fn [] (setNodes (add-node "unknown")))}
                         "Unknown")
                      ($ :button
                         {:class "btn btn-xs join-item"
                          :on-click (fn [] (setNodes (add-node "exile")))}
                         "Exile")
                      ($ :button
                         {:class "btn btn-xs join-item"
                          :on-click (fn [] (setNodes (add-node "firefighter")))}
                         "Firefighter")
                      ($ :button
                         {:class "btn btn-xs join-item"
                          :on-click (fn [] (setNodes (add-node "manager")))}
                         "Manager"))))
             ($ Background {:variant "dots"
                            :gap 12
                            :size 1}))))))
