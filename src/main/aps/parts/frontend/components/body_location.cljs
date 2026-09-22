(ns aps.parts.frontend.components.body-location
  "Pinpoint where in the client's body a Part is felt, on a body silhouette
   (ADR-0013).

   The silhouette is a single 600×600 asset holding a front figure (left half)
   and a back figure (right half). A figure is shown by cropping the shared
   asset to its half with a CSS `background-image`: `background-size 200% 100%`
   renders the square asset at twice the box width, and `background-position`
   left / right selects the front / back half (the box's 1:2 aspect keeps it
   undistorted). A click yields a normalized point
   `{:view \"front\"|\"back\" :x 0..1 :y 0..1}` — the shape stored in a Part's
   `body_location`. Precise placement happens in a floating window
   (ADR-0017) scoped to the selected Part; the Part form only previews."
  (:require
   [aps.parts.frontend.components.window :refer [window window-actions]]
   [aps.parts.frontend.state.windows :as windows]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect]]
   [uix.re-frame :as uix.rf]))

(def ^:private silhouette-url "/images/silhouette.svg")

(defn- clamp01 [n] (max 0 (min 1 n)))

(defn- event->point
  "Pointer event on a figure → a normalized point within it. The figure fills
   the rendered element, so the fraction across the element already is the
   0..1 figure coordinate."
  [view e]
  (let [rect (.getBoundingClientRect (.-currentTarget e))]
    {:view view
     :x    (clamp01 (/ (- (.-clientX e) (.-left rect)) (.-width rect)))
     :y    (clamp01 (/ (- (.-clientY e) (.-top rect)) (.-height rect)))}))

(defui figure
  "One silhouette figure (`view` = \"front\" / \"back\"), cropped from the
   shared asset. Draws the pin when `location` is on this view. When
   `on-place` is given, clicking reports the picked point and the cursor
   becomes a crosshair."
  [{:keys [view location on-place class style]}]
  ($ :div
     {:class      class
      :role       "img"
      :aria-label (str view " of body")
      :style      (merge {:position           "relative"
                          :aspectRatio        "1 / 2"
                          :backgroundImage    (str "url(" silhouette-url ")")
                          :backgroundSize     "200% 100%"
                          :backgroundRepeat   "no-repeat"
                          :backgroundPosition (if (= view "back") "right center" "left center")}
                         (when on-place {:cursor "crosshair"})
                         style)
      :on-click   (when on-place #(on-place (event->point view %)))}
     (when (= view (:view location))
       ;; The pin is sized as a fraction of the figure width (with a 1:1
       ;; aspect so it stays round), so it looks the same whether the figure
       ;; is the small sidebar preview or the large window one.
       ($ :div {:aria-hidden true
                :style       {:position      "absolute"
                              :left          (str (* 100 (:x location)) "%")
                              :top           (str (* 100 (:y location)) "%")
                              :width         "5%"
                              :aspectRatio   "1"
                              :transform     "translate(-50%, -50%)"
                              :borderRadius  "9999px"
                              :background    "#1d4ed8"
                              :border        "2px solid white"
                              :boxShadow     "0 0 2px rgba(0,0,0,0.5)"
                              :pointerEvents "none"}}))))

(defn- crop-top
  "Vertical offset (a `top` percent of the 3:2 preview window) that centers the
   figure on the pin, clamped so the window never scrolls past the figure: the
   1:2 figure is 3× the window's height, so its travel is [-200%, 0%]."
  [y]
  (-> (- 0.5 (* 3 y)) (* 100) (max -200) (min 0)))

(defui location-preview
  "Sidebar preview: a 3:2 window onto the figure, centered vertically on the
   pin. Clicking it opens the editor (`on-open`)."
  [{:keys [location on-open]}]
  ($ :div {:class    "relative mt-1 w-full cursor-pointer overflow-hidden rounded border border-base-300"
           :style    {:aspectRatio "3 / 2"}
           :on-click on-open}
     ($ figure {:view     (:view location)
                :location location
                :class    "w-full"
                :style    {:position "absolute"
                           :left     "0"
                           :top      (str (crop-top (:y location)) "%")}})))

(defui location-field
  "Body-location section of the Part form: a small read-only preview plus a
   button; both open the body-location window via `on-open`. The window
   saves on its own, so this field has no value to commit."
  [{:keys [location on-open]}]
  ($ :div {:class "mt-1"}
     ($ :div {:class "flex items-center justify-between"}
        ($ :label {:class "fieldset-label"} "Body location:")
        ($ :button {:type     "button"
                    :class    "btn btn-xs"
                    :on-click on-open}
           (if location "Edit" "Add")))
     (when location
       ($ location-preview {:location location
                            :on-open  on-open}))))

(defui body-location-window
  "The Body location floating window (ADR-0017). Scope: the selected
   Part, exactly one — otherwise it closes itself, so it never shows a
   Part the selection has left. Edits a draft `{:location point-or-nil}`
   keyed by Part: place a pin (or Remove pin), then Save; Cancel discards.
   Read-only while the canvas is: figures ignore clicks and the actions
   are hidden."
  []
  (let [part      (windows/scope-part (uix.rf/use-subscribe [:map/selected-parts]))
        editable? (uix.rf/use-subscribe [:canvas/editable?])
        part-id   (:id part)
        draft     (uix.rf/use-subscribe [:ui/window-draft :body-location part-id])
        saved     (:body_location part)
        location  (if draft (:location draft) saved)
        dirty?    (and (some? draft) (not= location saved))
        place!    (fn [loc]
                    (rf/dispatch [:window/set-draft :body-location part-id {:location loc}]))
        cancel!   #(rf/dispatch [:window/clear-draft :body-location part-id])
        save!     (fn []
                    (rf/dispatch [:map/part-update part-id {:body_location location}])
                    (cancel!))]
    (use-effect
     (fn []
       (when-not part
         (rf/dispatch [:window/close :body-location])))
     [part])
    (when part
      ($ window {:kind  :body-location
                 :class "body-location"
                 :title (str "Body location · " (:label part))}
         ($ :div {:class "flex justify-center gap-4"}
            (for [view ["front" "back"]]
              ($ :div {:key view :class "relative flex-1 min-w-0"}
                 ($ figure {:view     view
                            :location location
                            :on-place (when editable? place!)
                            :class    "w-full rounded border border-base-300"})
                 ;; Removing the pin edits the draft (Save still commits),
                 ;; so it is the figure's control, not a footer finish
                 ;; action: it sits in the corner of the figure holding
                 ;; the pin — the outer corner (front left, back right), away
                 ;; from the gap between the figures. A sibling of the
                 ;; figure, so a click on it never places a pin.
                 (when (and editable? (= view (:view location)))
                   ($ :button {:type     "button"
                               :class    (str "btn btn-xs absolute bottom-2 "
                                              (if (= view "front") "left-2" "right-2"))
                               :on-click #(place! nil)}
                      "Remove pin")))))
         (when editable?
           ;; Same gap as the body's top padding under the title bar.
           ($ :div {:class "mt-[0.7rem]"}
              ($ window-actions {:dirty?    dirty?
                                 :on-save   save!
                                 :on-cancel cancel!})))))))
