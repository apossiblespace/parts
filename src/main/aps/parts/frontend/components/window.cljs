(ns aps.parts.frontend.components.window
  "The floating-window primitive (ADR-0017): a native <dialog> in
   non-modal mode — the `open` attribute, never `showModal` — so there
   is no backdrop and the canvas stays live. Positioned absolutely
   inside `.map-view` (its offsetParent), which is also the clamp box.
   One window per kind; state and z-order live in `state/windows`.

   Drag is the title bar's pointer events (mouse and iPad alike);
   position is read back from the DOM so a viewport resize re-clamps
   without a stale closure. Escape closes the window unless a field
   inside already claimed it (`defaultPrevented` — the autosave form's
   revert). The canvas key handler ignores keys targeted inside a
   dialog, so Escape does one thing."
  (:require
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect use-ref]]
   [uix.re-frame :as uix.rf]))

(defn- size [^js dialog]
  {:width (.-offsetWidth dialog) :height (.-offsetHeight dialog)})

(defn- bounds [^js dialog]
  (let [parent (.-offsetParent dialog)]
    {:width (.-clientWidth parent) :height (.-clientHeight parent)}))

(defn- dom-pos [^js dialog]
  [(.-offsetLeft dialog) (.-offsetTop dialog)])

(defui window
  "Props: `kind` (its key in `[:ui :windows]`), `title`, optional `class`
   (the per-kind size lives in CSS), `children`. Renders nothing while
   the kind is closed."
  [{:keys [kind title class children]}]
  (let [{:keys [pos z]} (get (uix.rf/use-subscribe [:ui/windows]) kind)
        dialog-ref      (use-ref nil)
        ;; Pointer offset from the window's top-left while dragging.
        drag            (use-ref nil)
        open?           (some? pos)
        close!          #(rf/dispatch [:window/close kind])
        move!           (fn [p]
                          (let [d @dialog-ref]
                            (rf/dispatch [:window/move kind p (size d) (bounds d)])))]
    ;; On open: take focus so Escape lands here, clamp the default
    ;; position to the real view, and keep clamping as the viewport
    ;; changes.
    (use-effect
     (fn []
       (when-let [d @dialog-ref]
         (.focus d)
         (move! (dom-pos d))
         (let [on-resize #(move! (dom-pos d))]
           (.addEventListener js/window "resize" on-resize)
           #(.removeEventListener js/window "resize" on-resize))))
     ^:lint/disable [open? kind])
    (when open?
      (let [[x y] pos]
        ($ :dialog
           {:ref             dialog-ref
            :open            true
            :class           (str "floating-window " class)
            :tab-index       -1
            :aria-label      title
            :style           {:left   (str x "px")
                              :top    (str y "px")
                              :zIndex (+ 10 z)}
            :on-pointer-down #(rf/dispatch [:window/front kind])
            :on-key-down     (fn [^js e]
                               (when (and (= "Escape" (.-key e))
                                          (not (.-defaultPrevented e)))
                                 (close!)))}
           ($ :div
              {:class             "floating-window-title"
               :on-pointer-down   (fn [^js e]
                                    (when-not (.closest (.-target e) "button")
                                      (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                                      (let [[left top] (dom-pos @dialog-ref)]
                                        (reset! drag [(- (.-clientX e) left)
                                                      (- (.-clientY e) top)]))))
               :on-pointer-move   (fn [^js e]
                                    (when-let [[dx dy] @drag]
                                      (move! [(- (.-clientX e) dx) (- (.-clientY e) dy)])))
               :on-pointer-up     #(reset! drag nil)
               :on-pointer-cancel #(reset! drag nil)}
              ($ :span {:class "truncate text-xs font-bold"} title)
              ($ :button {:type       "button"
                          :class      "btn btn-xs btn-circle btn-ghost"
                          :aria-label "Close"
                          :on-click   close!}
                 "✕"))
           ($ :div {:class "floating-window-body"} children))))))

(defui floating-windows
  "Mounts one component per open kind. `kinds` maps kind → the consumer
   component that renders its `window`."
  [{:keys [kinds]}]
  (let [open (uix.rf/use-subscribe [:ui/windows])]
    (for [kind  (keys open)
          :let  [component (get kinds kind)]
          :when component]
      ($ component {:key (name kind)}))))
