(ns aps.parts.render.pdf
  "SVG → PDF transcoding via Apache FOP's `PDFTranscoder` (which
   extends Batik's transcoder framework). One seam: any SVG document
   string in, PDF bytes out. Page size defaults to A4 at 72 dpi —
   matches the document renderer's 595×842 viewBox so the transcode
   maps 1:1 to PDF page units with no scaling.

   Fonts: FOP is given exactly the files `fonts/font-files` resolves
   from `:render/font-dir` (no system-font auto-detection); see the
   fonts ns for why one wide-coverage family. FOP subsets on embed:
   a Latin-only PDF stays small despite the ~16MB source files.

   See ADR-0008. The dep (`org.apache.xmlgraphics/fop`) is used only by
   this namespace; if PDF is ever removed, the dependency can come
   out with it."
  (:require
   [aps.parts.render.fonts :as fonts]
   [com.brunobonacci.mulog :as mulog])
  (:import
   (java.io ByteArrayInputStream ByteArrayOutputStream StringReader)
   (org.apache.batik.transcoder TranscoderInput TranscoderOutput)
   (org.apache.fop.configuration DefaultConfigurationBuilder)
   (org.apache.fop.svg PDFTranscoder)))

(defn- fop-configuration
  "FOP font configuration: one `<font-triplet>` per entry in
   `fonts/svg-weights`, so the registered weights and the weights the
   SVG emitters use come from the same map. No `<auto-detect/>`:
   rendering must not depend on what the host has installed."
  []
  (let [urls    (update-vals (fonts/font-files)
                             #(str (.toURI ^java.io.File %)))
        triplet (fn [weight]
                  (str "<font-triplet name=\"" fonts/font-name
                       "\" style=\"normal\" weight=\"" weight "\"/>"))
        font-el (fn [style weights]
                  (str "<font embed-url=\"" (urls style) "\">"
                       (apply str (map triplet weights))
                       "</font>"))
        conf    (str "<fop version=\"1.0\"><fonts>"
                     (->> (group-by val fonts/svg-weights)
                          (map (fn [[style entries]]
                                 (font-el style (map key entries))))
                          (apply str))
                     "</fonts></fop>")]
    (.build (DefaultConfigurationBuilder.)
            (ByteArrayInputStream. (.getBytes conf "UTF-8")))))

(def ^:private ^PDFTranscoder transcoder
  "One PDFTranscoder for the JVM's life. Batik transcoders aren't
   thread-safe (they mutate a bridge context during transcode), so the
   `svg->pdf` call serialises via `locking`. The configuration is
   STORED once but FOP re-applies it per transcode — each call builds a
   fresh FontInfo and re-parses the OTFs (~60-75ms per export, measured
   on FOP 2.10). Acceptable for a click-triggered export; if volume
   ever makes it matter, the escape hatch is subclassing PDFTranscoder
   to retain a FontManager across calls."
  (doto (PDFTranscoder.)
    (.configure (fop-configuration))))

(defn svg->pdf
  "Transcode an SVG document string to PDF bytes. Returns a `byte[]`.
   Single-threaded under the hood (Batik transcoders mutate shared
   bridge state); concurrent calls queue.

   On transcode failure, logs the SVG length and Batik's exception
   message under `::transcode-failed` before rethrowing. The SVG body
   itself is never logged: it is built from Part labels, notes and
   body_location (clinical content), which must not leak into the logs."
  ^bytes [^String svg]
  (let [baos (ByteArrayOutputStream.)]
    (try
      (locking transcoder
        (.transcode transcoder
                    (TranscoderInput. (StringReader. svg))
                    (TranscoderOutput. baos)))
      (catch Exception e
        (mulog/log ::transcode-failed
                   :svg-length (count svg)
                   :error (.getMessage e))
        (throw e)))
    (.toByteArray baos)))
