(ns aps.parts.render.document
  "High-fidelity SVG of a Map for the printable client hand-out — the
   PDF export path (`/api/maps/:id/render.pdf`, transcoded via Apache
   FOP). Orchestrates the four sub-namespaces:

   - `aps.parts.render.document.shapes`  — `<symbol>` loading, `<use>` placement, content viewBox
   - `aps.parts.render.document.labels`  — FontMetrics wrap + `<text>` emission
   - `aps.parts.render.document.edges`   — bezier / bowed-quadratic edges + per-type arrowhead markers
   - `aps.parts.render.document.chrome`  — page constants, title/date header, footer

   Renders **structure only** by default: shapes, labels, Relationship
   lines. Clinical fields (`notes`, `body_location`) are excluded
   because the PDF is a client-facing hand-out (see ADR-0008).

   For the low-fidelity glanceable thumbnail used on the Maps list,
   see `aps.parts.render.preview`. The two renderers exist for
   different *jobs* (recognition vs. print artifact), not different
   output formats."
  (:require
   [aps.parts.common.geometry :as geometry]
   [aps.parts.render.document.chrome :as chrome]
   [aps.parts.render.document.edges :as edges]
   [aps.parts.render.document.labels :as labels]
   [aps.parts.render.document.shapes :as shapes]
   [hiccup.util :refer [raw-string]]
   [hiccup2.core :refer [html]])
  (:import
   (java.time LocalDate)))

(def ^:private xmlns-xlink (keyword "xmlns:xlink"))

(defn render
  "Render a hydrated Map to a document-grade SVG string with page
   chrome — title + date header, 'Made with Parts' footer, A4 page
   viewBox sized to Batik's PDF defaults. The Map content sits in a
   nested `<svg>` whose viewBox auto-scales to fit the content area.

   Input shape:

     {:title         \"Smith Map\"
      :parts         [{:id :type :label
                       :position_x :position_y :width :height} …]
      :relationships [{:id :source_id :target_id :type} …]}

   Optional opts: `:as-of-date` — a `java.time.LocalDate` for the
   header date (defaults to today; an as-of export passes the viewed
   Session's date). `:subtitle` — a quiet line under the title, e.g.
   \"As of Session 1\"."
  ([the-map] (render the-map {}))
  ([{:keys [title parts relationships] :as _the-map}
    {:keys [as-of-date subtitle]}]
   (let [[vx vy vw vh]          (shapes/viewbox parts)
         by-id                  (into {} (map (juxt :id identity)) parts)
         bidi                   (geometry/bidirectional-pairs relationships)
         as-of                  (or as-of-date (LocalDate/now))
         {pw :width ph :height} chrome/page
         inner-x                chrome/page-margin
         inner-y                (+ chrome/header-band-height chrome/page-margin)
         inner-w                (- pw (* 2 chrome/page-margin))
         inner-h                (- ph chrome/header-band-height
                                   chrome/footer-band-height
                                   (* 2 chrome/page-margin))]
     (str
      (html
       ;; `width`/`height` (not just `viewBox`) are required for Batik
       ;; to derive PDF page dimensions — `SVGAbstractTranscoder`
       ;; defaults to 400×400 when intrinsic size is absent. SVG length
       ;; units are 1/72 inch (PostScript points), so 595×842 maps
       ;; directly to A4 with no scaling.
       [:svg {:xmlns      "http://www.w3.org/2000/svg"
              xmlns-xlink "http://www.w3.org/1999/xlink"
              :width      pw
              :height     ph
              :viewBox    (str "0 0 " pw " " ph)}
        [:defs
         (raw-string @shapes/shape-symbols)
         edges/edge-arrow-markers]
        (chrome/header-block title as-of subtitle)
        (chrome/footer-block)
        ;; Nested <svg> auto-scales the map's natural bounding box into
        ;; the content area.
        [:svg {:x       inner-x
               :y       inner-y
               :width   inner-w
               :height  inner-h
               :viewBox (str vx " " vy " " vw " " vh)}
         (keep (partial edges/relationship-path by-id bidi) relationships)
         (map shapes/part-use parts)
         (keep labels/part-label parts)]])))))
