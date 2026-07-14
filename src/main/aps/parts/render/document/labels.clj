(ns aps.parts.render.document.labels
  "Part-label rendering for the document SVG. SVG `<text>` does not
   auto-wrap, so we compute line breaks server-side with
   `java.awt.FontMetrics` (via `Font/getStringBounds`). Avoids
   `<foreignObject>`, whose Batik support is incomplete (see ADR-0008).

   Font measurement uses Liberation Sans if the JVM can resolve it
   (true on production Linux); otherwise AWT silently falls back to a
   logical font, which measures slightly differently. The SVG declares
   the same fallback chain so the browser / Batik render against the
   closest match they have."
  (:require
   [clojure.string :as str])
  (:import
   (java.awt Font RenderingHints)
   (java.awt.font FontRenderContext)
   (java.awt.geom AffineTransform)))

(def ^:private label-font-size 14)
(def ^:private label-line-height 17)

(def font-name
  "The physical font both measurement (AWT) and the SVG output name —
   chrome builds its title/meta fonts from this too, so a font swap is
   one edit."
  "Liberation Sans")

(def label-font-family
  "SVG `font-family` string — shared with the chrome header/footer so
   one font choice applies across the whole document."
  (str "\"" font-name "\", Arial, sans-serif"))

(def ^:private label-max-lines 3)
(def ^:private label-inset
  "Horizontal padding inside a Part's box: usable text width is
   `width − 2 × label-inset`. Keeps labels off the shape outline."
  10)
(def ^:private label-ellipsis "…")

(def ^:private ^Font label-font
  (Font. font-name Font/PLAIN label-font-size))

(def ^:private ^FontRenderContext frc
  ;; RenderingHints constants rather than booleans: Clojure boxes booleans to
  ;; `Boolean`, which makes the (AffineTransform, Object, Object) overload
  ;; match instead of (AffineTransform, boolean, boolean) — and that overload
  ;; validates the values, rejecting Boolean with "AA hint:true".
  (FontRenderContext. (AffineTransform.)
                      RenderingHints/VALUE_TEXT_ANTIALIAS_ON
                      RenderingHints/VALUE_FRACTIONALMETRICS_ON))

(defn text-width
  "Rendered width of `s` in `font`, in px. Public so the chrome header
   can measure its own (larger) title font with the same metrics."
  [^Font font ^String s]
  (.getWidth (.getStringBounds font s frc)))

(defn- wrap-to-lines
  "Greedy word-wrap `text` into lines whose width does not exceed
   `max-px`. A single word wider than the box becomes its own line —
   we do not character-wrap; Part names are usually short enough that
   this degenerate case doesn't matter visually."
  [font text max-px]
  (loop [words   (remove str/blank? (str/split text #"\s+"))
         current ""
         lines   []]
    (if (empty? words)
      (if (empty? current) lines (conj lines current))
      (let [word    (first words)
            attempt (if (empty? current) word (str current " " word))]
        (if (<= (text-width font attempt) max-px)
          (recur (rest words) attempt lines)
          (if (empty? current)
            (recur (rest words) "" (conj lines word))
            (recur words "" (conj lines current))))))))

(defn- truncate-with-ellipsis
  "Shrink `text` one character at a time from the right until
   `text + ellipsis` fits within `max-px`."
  [font text max-px]
  (loop [s text]
    (cond
      (empty? s)
      label-ellipsis

      (<= (text-width font (str s label-ellipsis)) max-px)
      (str s label-ellipsis)

      :else
      (recur (subs s 0 (dec (count s)))))))

(defn wrap-capped
  "Greedy word-wrap `text` in `font` into at most `max-lines` lines of
   `max-px`; the last line absorbs any remainder and is truncated with
   an ellipsis. The one wrapping mechanism for Part labels and the
   chrome's title alike."
  [font text max-px max-lines]
  (let [lines (wrap-to-lines font text max-px)]
    (if (<= (count lines) max-lines)
      lines
      (let [kept  (vec (take (dec max-lines) lines))
            spill (str/join " " (drop (dec max-lines) lines))]
        (conj kept (truncate-with-ellipsis font spill max-px))))))

(defn- wrap-label
  "Wrap `label` to fit `box-width-px`, capped at `label-max-lines`."
  [label box-width-px]
  (wrap-capped label-font label
               (- box-width-px (* 2 label-inset))
               label-max-lines))

(defn part-label
  "A `<text>` element holding wrapped label lines for one Part, centred
   on the Part's box. Returns nil when the label is nil or blank."
  [{:keys [label position_x position_y width height]}]
  (when-let [text (some-> label str/trim not-empty)]
    (let [w        (or width  100)
          h        (or height 100)
          cx       (+ position_x (/ w 2))
          cy       (+ position_y (/ h 2))
          lines    (wrap-label text w)
          n-lines  (count lines)
          first-cy (- cy (* (/ (dec n-lines) 2.0) label-line-height))]
      [:text {:x                 cx
              :y                 first-cy
              :text-anchor       "middle"
              :dominant-baseline "middle"
              :font-family       label-font-family
              :font-size         label-font-size
              :font-weight       500
              :fill              "currentColor"}
       (map-indexed
        (fn [i line]
          [:tspan {:x  cx
                   :dy (if (zero? i) 0 label-line-height)}
           line])
        lines)])))
