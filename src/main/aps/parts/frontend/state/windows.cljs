(ns aps.parts.frontend.state.windows
  "Pure state for floating windows (ADR-0017): which kinds are open,
   where each sits, how big it is, which is in front, and the drafts
   each holds. Lives under `[:ui :windows]` as

     {kind {:open?  bool
            :pos    [x y]
            :size   [w h]          ; nil until the user resizes
            :z      n
            :drafts {entity-id draft}}}

   A closed kind keeps its entry: position, size and drafts live for the
   page's life (Close keeps the draft; Cancel is the only discard).
   Nothing persists across a reload.

   Re-frame-free so the kaocha cljs suite can unit-test it; the events
   in `state/handlers` and the `components/window` primitive consume it.")

(def ^:private fallback-pos [16 96])

(def default-pos
  "Where a kind opens the first time in a page's life: under the top
   chrome, clear of the sidebar (top-right) and the palette (bottom-
   centre), each kind a step from the last so two never stack exactly.
   Kinds not listed use `fallback-pos`."
  {:body-location [16 96]
   :notes         [28 112]
   :trigger       [40 128]
   :conversation  [52 144]})

(def min-size
  "Smallest a window can be resized to, in px: enough for a title bar
   and a few lines."
  [240 160])

(defn- next-z [windows]
  (inc (reduce max 0 (map :z (vals windows)))))

(defn open?
  [windows kind]
  (boolean (get-in windows [kind :open?])))

(defn open-kinds [windows]
  (into [] (comp (filter (fn [[_ w]] (:open? w))) (map key)) windows))

(defn open
  "Open `kind` at its last position (or its default) and bring it to
   front. Reopening an open window only brings it to front."
  [windows kind]
  (update windows kind
          (fn [w]
            (assoc w
                   :open? true
                   :pos   (or (:pos w) (get default-pos kind fallback-pos))
                   :z     (next-z windows)))))

(defn close
  "Hide `kind`; its position, size and drafts stay."
  [windows kind]
  (if (contains? windows kind)
    (assoc-in windows [kind :open?] false)
    windows))

(defn front
  "Bring an open `kind` to the front; a no-op for a closed one."
  [windows kind]
  (if (open? windows kind)
    (assoc-in windows [kind :z] (next-z windows))
    windows))

(defn clamp-pos
  "Keep the whole window inside the view. A window larger than the view
   pins to the top-left edge so its title bar stays reachable."
  [[x y] {:keys [width height]} {view-w :width view-h :height}]
  [(-> x (min (- view-w width)) (max 0))
   (-> y (min (- view-h height)) (max 0))])

(defn move
  "Move an open `kind` to `pos`, clamped to `bounds` given the window's
   rendered `size`; a no-op for a closed one (a drag that outlives its
   window)."
  [windows kind pos size bounds]
  (if (open? windows kind)
    (assoc-in windows [kind :pos] (clamp-pos pos size bounds))
    windows))

(defn clamp-size
  "Keep a window at least `min-size` and inside the view from `pos`."
  [[w h] [x y] {view-w :width view-h :height}]
  (let [[min-w min-h] min-size]
    [(-> w (min (- view-w x)) (max min-w))
     (-> h (min (- view-h y)) (max min-h))]))

(defn resize
  "Resize an open `kind` to `size`, clamped from its current position;
   a no-op for a closed one."
  [windows kind size bounds]
  (if (open? windows kind)
    (update windows kind
            (fn [w] (assoc w :size (clamp-size size (:pos w) bounds))))
    windows))

(defn draft
  "The draft `kind` holds for `entity-id`, or nil."
  [windows kind entity-id]
  (get-in windows [kind :drafts entity-id]))

(defn set-draft
  "Hold `value` as the draft for `entity-id`; a draft may be set on a
   closed kind (it opens later showing it)."
  [windows kind entity-id value]
  (assoc-in windows [kind :drafts entity-id] value))

(defn clear-draft
  "Cancel or Save: the draft is gone."
  [windows kind entity-id]
  (if (contains? windows kind)
    (update-in windows [kind :drafts] dissoc entity-id)
    windows))

(defn scope-part
  "The Part a selection-scoped window shows: exactly one selected Part,
   else nil — and nil means the window closes (ADR-0017: a window never
   pins to a Part the selection has left)."
  [selected-parts]
  (when (= 1 (count selected-parts))
    (first selected-parts)))
