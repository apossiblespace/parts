(ns aps.parts.render.pdf
  "SVG → PDF transcoding via Apache FOP's `PDFTranscoder` (which
   extends Batik's transcoder framework). One seam: any SVG document
   string in, PDF bytes out. Page size defaults to A4 at 72 dpi —
   matches the document renderer's 595×842 viewBox so the transcode
   maps 1:1 to PDF page units with no scaling.

   See ADR-0008. The dep (`org.apache.xmlgraphics/fop`) is used only by
   this namespace; if PDF is ever removed, the dependency can come
   out with it."
  (:require
   [com.brunobonacci.mulog :as mulog])
  (:import
   (java.io ByteArrayOutputStream StringReader)
   (org.apache.batik.transcoder TranscoderInput TranscoderOutput)
   (org.apache.fop.svg PDFTranscoder)))

(def ^:private ^PDFTranscoder transcoder
  "One PDFTranscoder for the JVM's life. Batik transcoders aren't
   thread-safe (they mutate a bridge context during transcode), so the
   `svg->pdf` call serialises via `locking`. Per-request instantiation
   would re-pay FOP's font-discovery cost (~100-500ms on Linux) on
   every PDF; one instance amortises that across the JVM's lifetime."
  (PDFTranscoder.))

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
