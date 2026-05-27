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

(def label-font-family
  "SVG `font-family` string — shared with the chrome header/footer so
   one font choice applies across the whole document."
  "\"Liberation Sans\", Arial, sans-serif")

(def ^:private label-max-lines 3)
(def ^:private label-inset
  "Horizontal padding inside a Part's box: usable text width is
   `width − 2 × label-inset`. Keeps labels off the shape outline."
  10)
(def ^:private label-ellipsis "…")

(def ^:private ^Font label-font
  (Font. "Liberation Sans" Font/PLAIN label-font-size))

(def ^:private ^FontRenderContext frc
  ;; RenderingHints constants rather than booleans: Clojure boxes booleans to
  ;; `Boolean`, which makes the (AffineTransform, Object, Object) overload
  ;; match instead of (AffineTransform, boolean, boolean) — and that overload
  ;; validates the values, rejecting Boolean with "AA hint:true".
  (FontRenderContext. (AffineTransform.)
                      RenderingHints/VALUE_TEXT_ANTIALIAS_ON
                      RenderingHints/VALUE_FRACTIONALMETRICS_ON))

(defn- string-width
  [^String s]
  (.getWidth (.getStringBounds label-font s frc)))

(defn- wrap-to-lines
  "Greedy word-wrap `text` into lines whose width does not exceed
   `max-px`. A single word wider than the box becomes its own line —
   we do not character-wrap; Part names are usually short enough that
   this degenerate case doesn't matter visually."
  [text max-px]
  (loop [words   (remove str/blank? (str/split text #"\s+"))
         current ""
         lines   []]
    (if (empty? words)
      (if (empty? current) lines (conj lines current))
      (let [word    (first words)
            attempt (if (empty? current) word (str current " " word))]
        (if (<= (string-width attempt) max-px)
          (recur (rest words) attempt lines)
          (if (empty? current)
            (recur (rest words) "" (conj lines word))
            (recur words "" (conj lines current))))))))

(defn- truncate-with-ellipsis
  "Shrink `text` one character at a time from the right until
   `text + ellipsis` fits within `max-px`."
  [text max-px]
  (loop [s text]
    (cond
      (empty? s)
      label-ellipsis

      (<= (string-width (str s label-ellipsis)) max-px)
      (str s label-ellipsis)

      :else
      (recur (subs s 0 (dec (count s)))))))

(defn- wrap-label
  "Wrap `label` to fit `box-width-px`, capped at `label-max-lines`.
   Last line absorbs any remainder and is truncated with an ellipsis."
  [label box-width-px]
  (let [usable (- box-width-px (* 2 label-inset))
        lines  (wrap-to-lines label usable)]
    (if (<= (count lines) label-max-lines)
      lines
      (let [kept  (vec (take (dec label-max-lines) lines))
            spill (str/join " " (drop (dec label-max-lines) lines))]
        (conj kept (truncate-with-ellipsis spill usable))))))

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
