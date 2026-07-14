(ns aps.parts.render.document.chrome
  "Page chrome for the document SVG: title + date header, 'Made with
   Parts' footer, plus the A4 page dimensions Apache FOP's
   PDFTranscoder maps 1:1 to PDF page units (PostScript points)."
  (:require
   [aps.parts.common.models.map :as map-model]
   [aps.parts.render.document.labels :as labels])
  (:import
   (java.awt Font)
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

(def ^:private title-font-size 22)
(def ^:private title-line-height 26)
(def ^:private title-max-lines 2)
(def ^:private meta-font-size 12)
(def ^:private title-meta-gap 16)

(def ^:private ^Font title-font
  (Font. labels/font-name Font/BOLD title-font-size))

(def ^:private ^Font meta-font
  (Font. labels/font-name Font/PLAIN meta-font-size))

(defn header-block
  "Top band: Map title on the left; on the right one quiet line — the
   date, prefixed by the subtitle when given (\"As of Session 1,
   5 July 2026\" on an as-of export). The title word-wraps against the
   right line's measured width and caps at two lines with an ellipsis:
   SVG text neither wraps nor clips on its own, so an unwrapped long
   title would run under the date. Falls back to
   `map-model/default-title` when the title is missing."
  [title as-of subtitle]
  (let [right-text   (if subtitle
                       (str subtitle ", " (format-date as-of))
                       (format-date as-of))
        avail        (- (:width page) (* 2 page-margin)
                        (labels/text-width meta-font right-text)
                        title-meta-gap)
        lines        (labels/wrap-capped
                      title-font
                      (or (not-empty title) map-model/default-title)
                      avail title-max-lines)
        single-line? (= 1 (count lines))
        ;; Two lines sit 30/56 inside the 64px band; one line keeps the
        ;; original 48 baseline. The right line aligns with the first.
        first-y      (if single-line?
                       (- header-band-height 16)
                       (- header-band-height 34))]
    [:g
     [:text {:x           page-margin
             :y           first-y
             :font-family labels/label-font-family
             :font-size   title-font-size
             :font-weight 600
             :fill        "#1a1a1a"}
      ;; tspans only when the title actually wraps — a single-line
      ;; title stays plain text (tests key on Part labels being the
      ;; document's usual tspan source).
      (if single-line?
        (first lines)
        (map-indexed
         (fn [i line]
           [:tspan {:x  page-margin
                    :dy (if (zero? i) 0 title-line-height)}
            line])
         lines))]
     [:text {:x           (- (:width page) page-margin)
             :y           first-y
             :text-anchor "end"
             :font-family labels/label-font-family
             :font-size   meta-font-size
             :fill        "#888"}
      right-text]]))

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
