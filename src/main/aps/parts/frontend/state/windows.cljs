(ns aps.parts.frontend.state.windows
  "Pure state for floating windows (ADR-0017): which kinds are open,
   where each sits, and which is in front. Lives under `[:ui :windows]`
   as `{kind {:pos [x y] :z n}}` — a kind is open when its key is
   present. Positions are page-lifetime only; nothing persists.

   Re-frame-free so the kaocha cljs suite can unit-test it; the events
   in `state/handlers` and the `components/window` primitive consume it.")

(def ^:private fallback-pos [16 96])

(def default-pos
  "Where a kind opens the first time in a page's life: under the top
   chrome, clear of the sidebar (top-right) and the palette (bottom-
   centre). Kinds not listed use `fallback-pos`."
  {:body-location [16 96]})

(defn- next-z [windows]
  (inc (reduce max 0 (map :z (vals windows)))))

(defn open
  "Open `kind` at its last position (or its default) and bring it to
   front. Reopening an open window only brings it to front."
  [windows kind]
  (assoc windows kind {:pos (get-in windows [kind :pos]
                                    (get default-pos kind fallback-pos))
                       :z   (next-z windows)}))

(defn close [windows kind]
  (dissoc windows kind))

(defn front
  "Bring an open `kind` to the front; a no-op for a closed one."
  [windows kind]
  (if (contains? windows kind)
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
  (if (contains? windows kind)
    (assoc-in windows [kind :pos] (clamp-pos pos size bounds))
    windows))

(defn scope-part
  "The Part a selection-scoped window shows: exactly one selected Part,
   else nil — and nil means the window closes (ADR-0017: a window never
   pins to a Part the selection has left)."
  [selected-parts]
  (when (= 1 (count selected-parts))
    (first selected-parts)))
