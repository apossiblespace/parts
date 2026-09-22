(ns aps.parts.frontend.components.window
  "The floating-window primitive (ADR-0017): a native <dialog> in
   non-modal mode — the `open` attribute, never `showModal` — so there
   is no backdrop and the canvas stays live. Positioned absolutely
   inside `.map-view` (its offsetParent), which is also the clamp box.
   One window per kind; position, size, z-order and drafts live in
   `state/windows`.

   Drag is the title bar's pointer events, resize the corner handle's
   (mouse and iPad alike; CSS `resize` draws no grip on iOS). Position
   is read back from the DOM so a viewport resize re-clamps without a
   stale closure. Escape closes the window unless a field inside already
   claimed it (`defaultPrevented`). The canvas key handler ignores keys
   targeted inside a dialog, so Escape does one thing.

   Saving is the consumer's: a window edits a draft and offers Save /
   Cancel (`window-actions`, which also shows the save status); Close
   keeps the draft."
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

(def ^:private grip
  ;; The two diagonal strokes a native textarea's resize corner draws.
  ($ :svg {:width 9 :height 9 :viewBox "0 0 9 9" :aria-hidden true}
     ($ :path {:d              "M8.5 1 L1 8.5 M8.5 5 L5 8.5"
               :stroke         "currentColor"
               :stroke-width   1
               :stroke-linecap "round"})))

(defn- use-pointer-drag
  "Pointer-capture drag on the element that gets these handlers. `on-move`
   receives the pointer's client [x y] minus the offset recorded at
   pointer-down by `origin` (fn [event] -> [x y]). Buttons inside the
   handle are left alone so their clicks still land."
  [origin on-move]
  (let [drag (use-ref nil)]
    {:on-pointer-down   (fn [^js e]
                          (when-not (.closest (.-target e) "button")
                            (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                            (let [[ox oy] (origin e)]
                              (reset! drag [(- (.-clientX e) ox)
                                            (- (.-clientY e) oy)]))))
     :on-pointer-move   (fn [^js e]
                          (when-let [[dx dy] @drag]
                            (on-move [(- (.-clientX e) dx) (- (.-clientY e) dy)])))
     :on-pointer-up     #(reset! drag nil)
     :on-pointer-cancel #(reset! drag nil)}))

(defui window
  "Props: `kind` (its key in `[:ui :windows]`), `title`, optional
   `class` (the per-kind default size lives in CSS), `children`.
   Renders nothing while the kind is closed."
  [{:keys [kind title class children]}]
  (let [{:keys [open? pos z] [w h] :size} (uix.rf/use-subscribe [:ui/window kind])
        dialog-ref                        (use-ref nil)
        close!                            #(rf/dispatch [:window/close kind])
        move!                             (fn [p]
                                            (let [d @dialog-ref]
                                              (rf/dispatch [:window/move kind p (size d) (bounds d)])))
        resize!                           (fn [s]
                                            (rf/dispatch [:window/resize kind s (bounds @dialog-ref)]))
        title-drag                        (use-pointer-drag
                                           (fn [_] (dom-pos @dialog-ref))
                                           move!)
        ;; Recording the current size as the origin makes the drag's
        ;; output `size + pointer travel` — the new size.
        corner-drag                       (use-pointer-drag
                                           (fn [_]
                                             (let [d @dialog-ref]
                                               [(.-offsetWidth d) (.-offsetHeight d)]))
                                           resize!)]
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
            :style           (cond-> {:left   (str x "px")
                                      :top    (str y "px")
                                      :zIndex (+ 10 z)}
                               w (assoc :width (str w "px") :height (str h "px")))
            :on-pointer-down #(rf/dispatch [:window/front kind])
            :on-key-down     (fn [^js e]
                               (when (and (= "Escape" (.-key e))
                                          (not (.-defaultPrevented e)))
                                 (close!)))}
           ($ :div (merge {:class "floating-window-title"} title-drag)
              ($ :span {:class "truncate text-xs font-bold"} title)
              ($ :button {:type       "button"
                          :class      "btn btn-xs btn-circle btn-ghost"
                          :aria-label "Close"
                          :on-click   close!}
                 "✕"))
           ($ :div {:class "floating-window-body"} children)
           ($ :div (merge {:class      "floating-window-resize"
                           :aria-label "Resize"}
                          corner-drag)
              grip))))))

(defui window-actions
  "The footer every editing window ends with: the save status on the
   left (quiet when clean, like the map status indicator), Discard
   changes and Save on the right. Both buttons disabled while there is nothing to
   save or discard."
  [{:keys [dirty? on-save on-cancel]}]
  ($ :div {:class "floating-window-actions"}
     ($ :span {:class "floating-window-status" :role "status"}
        (when dirty?
          ($ :<>
             ($ :span {:class "floating-window-dirty" :aria-hidden true})
             "Unsaved changes")))
     ($ :button {:type     "button"
                 :class    "btn btn-sm"
                 :disabled (not dirty?)
                 :on-click on-cancel}
        "Discard changes")
     ($ :button {:type     "button"
                 :class    "btn btn-sm btn-primary"
                 :disabled (not dirty?)
                 :on-click on-save}
        "Save")))

(defui floating-windows
  "Mounts one component per open kind. `kinds` maps kind → the consumer
   component that renders its `window`."
  [{:keys [kinds]}]
  (let [windows (uix.rf/use-subscribe [:ui/windows])]
    (for [[kind {:keys [open?]}] windows
          :let                   [component (get kinds kind)]
          :when                  (and open? component)]
      ($ component {:key (name kind)}))))
