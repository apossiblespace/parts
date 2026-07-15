(ns aps.parts.render.fonts
  "The document font: Noto Sans CJK TC, operator-installed and resolved
   from `:render/font-dir`. One font for everything because FOP does no
   per-glyph font fallback (a glyph missing from the selected font
   renders as a literal `#`), so the single selected font must itself
   cover every script a label can hold — the CJK repertoire plus
   Latin/Greek/Cyrillic here. TC gives shared Han ideographs their
   traditional-script shapes; simplified text still has full coverage
   (SC/TC is a glyph-style split, not a coverage split).

   Measurement (AWT, `document-font`) and embedding (FOP, `render.pdf`)
   load the same files, so wrapping cannot drift from rendering. The
   files are operator-installed, not bundled: the Nix dev shell exports
   PARTS__RENDER__FONT_DIR; Ubuntu hosts install them per the runbook.
   Loading fails fast when they are missing — a missing font would
   otherwise degrade to `#` glyphs in a client-facing hand-out, so a
   loud boot failure beats a quiet one."
  (:require
   [aps.parts.config :as config]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.awt Font)))

(def font-name
  "The family name inside the font files — the SVG `font-family` and
   FOP's registered triplets must both use this exact name."
  "Noto Sans CJK TC")

(def font-family
  "SVG `font-family` string shared by every text element in the
   document, so one font choice applies across the whole document."
  (str "\"" font-name "\", sans-serif"))

(def svg-weights
  "Every `font-weight` the document's SVG emits → the style whose file
   renders it (text with no font-weight attribute is \"normal\").
   `render.pdf` registers exactly these as FOP triplets, so a weight an
   emitter uses without a mapping here would fall to FOP's silent
   closest-match substitution — add the mapping when introducing a new
   weight."
  {"normal" :regular
   "500"    :regular
   "600"    :bold})

(defn- font-file
  [dir filename]
  (let [f (io/file dir filename)]
    (when-not (.isFile f)
      (throw (ex-info (str "Document font missing: " f)
                      {:type :config-error :dir dir :file filename})))
    f))

(defn font-files
  "The document font files by style, resolved from `:render/font-dir`
   and verified to exist. Throws when the directory is unset or a file
   is missing."
  []
  (let [dir (config/render-font-dir)]
    (when (str/blank? dir)
      (throw (ex-info (str ":render/font-dir is not set — the PDF renderer "
                           "needs the Noto Sans CJK TC files. Set "
                           "PARTS__RENDER__FONT_DIR (the Nix dev shell "
                           "exports it; see the runbook for hosts).")
                      {:type :config-error})))
    {:regular (font-file dir "NotoSansCJKtc-Regular.otf")
     :bold    (font-file dir "NotoSansCJKtc-Bold.otf")}))

(def ^:private base-fonts
  (update-vals (font-files)
               #(Font/createFont Font/TRUETYPE_FONT ^java.io.File %)))

(defn document-font
  "The document font at `size` pt, `style` :regular or :bold."
  ^Font [style size]
  (.deriveFont ^Font (base-fonts style) (float size)))
