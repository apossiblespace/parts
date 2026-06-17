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
   `body_location`."
  (:require
   [aps.parts.frontend.components.modal :refer [modal]]
   [uix.core :refer [$ defui use-state]]))

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
       ;; is the small sidebar preview or the large modal one.
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
   button opening a modal with both figures for precise placement. `on-change`
   receives the new point, or nil when cleared; the surrounding form persists
   it on Save like any other field."
  [{:keys [location on-change]}]
  (let [[open? set-open] (use-state false)]
    ($ :div {:class "mt-1"}
       ($ :div {:class "flex items-center justify-between"}
          ($ :label {:class "fieldset-label"} "Body location:")
          ($ :button {:type     "button"
                      :class    "btn btn-xs"
                      :on-click #(set-open true)}
             (if location "Edit" "Add")))
       (when location
         ($ location-preview {:location location
                              :on-open  #(set-open true)}))
       ($ modal {:show      open?
                 :title     "Body location"
                 :on-close  #(set-open false)
                 :box-class "max-w-3xl"}
          ($ :div {:class "flex justify-center gap-4"}
             (for [view ["front" "back"]]
               ($ figure {:key      view
                          :view     view
                          :location location
                          :on-place on-change
                          :class    "flex-1 min-w-0 rounded border border-base-300"})))
          ($ :div {:class "flex justify-between mt-3"}
             ($ :button {:type     "button"
                         :class    "btn btn-sm btn-ghost"
                         :disabled (nil? location)
                         :on-click #(on-change nil)}
                "Clear")
             ($ :button {:type     "button"
                         :class    "btn btn-sm btn-primary"
                         :on-click #(set-open false)}
                "Done"))))))
