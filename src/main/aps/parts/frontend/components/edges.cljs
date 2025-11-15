(ns aps.parts.frontend.components.edges
  (:require
   ["@xyflow/react" :refer [BaseEdge getBezierPath]]
   [uix.core :refer [$ as-react defui]]))

;; TODO: This is WIP, we need to review all the props being passed here.
(defui parts-edge [{:keys [id data source-x source-y target-x target-y source-position target-position]}]
  (let [[edge-path] (getBezierPath #js {:sourceX        source-x
                                        :sourceY        source-y
                                        :targetX        target-x
                                        :targetY        target-y
                                        :sourcePosition source-position
                                        :targetPosition target-position})
        class-name  (str "edge edge-" (:relationship data))]
    ($ BaseEdge {:path      edge-path
                 :className class-name
                 :id        id})))

;; Props received by an Edge component:
;;
;; export type EdgeProps<EdgeType extends Edge = Edge> = {
;;   id: string;
;;   animated: boolean;
;;   data: EdgeType['data'];
;;   style: React.CSSProperties;
;;   selected: boolean;
;;   source: string;
;;   target: string;
;;   sourceHandleId?: string | null;
;;   targetHandleId?: string | null;
;;   interactionWidth: number;
;;   sourceX: number;
;;   sourceY: number;
;;   targetX: number;
;;   targetY: number;
;;   sourcePosition: Position;
;;   targetPosition: Position;
;;   label?: string | React.ReactNode;
;;   labelStyle?: React.CSSProperties;
;;   labelShowBg?: boolean;
;;   labelBgStyle?: CSSProperties;
;;   labelBgPadding?: [number, number];
;;   labelBgBorderRadius?: number;
;;   markerStart?: string;
;;   markerEnd?: string;
;;   pathOptions?: any;
;; };
(def PartsEdge
  (as-react
   (fn [{:keys [id data sourceX sourceY targetX targetY sourcePosition targetPosition]}]
     ($ parts-edge {:id              id
                    :data            (js->clj data :keywordize-keys true)
                    :source-x        sourceX
                    :source-y        sourceY
                    :target-x        targetX
                    :target-y        targetY
                    :source-position sourcePosition
                    :target-position targetPosition}))))

(def edge-types
  {"default" PartsEdge})
