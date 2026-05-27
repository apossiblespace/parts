(ns aps.parts.render.document.chrome
  "Page chrome for the document SVG: title + date header, 'Made with
   Parts' footer, plus the A4 page dimensions Apache FOP's
   PDFTranscoder maps 1:1 to PDF page units (PostScript points)."
  (:require
   [aps.parts.common.models.map :as map-model]
   [aps.parts.render.document.labels :as labels])
  (:import
   (java.time LocalDate)
   (java.time.format DateTimeFormatter)))

(def page
  "Page dimensions in PostScript points — A4 portrait. Batik's
   PDFTranscoder defaults to A4 at 72dpi, so 595×842 maps 1:1 to PDF
   page units."
  {:width 595 :height 842})

(def page-margin 32)
(def header-band-height 64)
(def footer-band-height 32)

(def ^:private date-formatter
  (DateTimeFormatter/ofPattern "d MMMM yyyy"))

(defn- format-date
  [^LocalDate d]
  (.format d date-formatter))

(defn header-block
  "Top band: Map title on the left, date on the right. Title falls
   back to `map-model/default-title` when missing."
  [title as-of]
  [:g
   [:text {:x           page-margin
           :y           (- header-band-height 16)
           :font-family labels/label-font-family
           :font-size   22
           :font-weight 600
           :fill        "#1a1a1a"}
    (or (not-empty title) map-model/default-title)]
   [:text {:x           (- (:width page) page-margin)
           :y           (- header-band-height 16)
           :text-anchor "end"
           :font-family labels/label-font-family
           :font-size   12
           :fill        "#888"}
    (format-date as-of)]])

(defn footer-block
  "Bottom band: 'Made with Parts' centred."
  []
  [:g
   [:text {:x           (/ (:width page) 2)
           :y           (- (:height page) 14)
           :text-anchor "middle"
           :font-family labels/label-font-family
           :font-size   11
           :fill        "#aaa"}
    "Made with Parts: https://parts.ifs.tools"]])
