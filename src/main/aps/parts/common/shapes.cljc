(ns aps.parts.common.shapes
  "Part-shape artwork as SVG `<symbol>`s, for both the canvas and the
   document Render. The four files in `resources/public/images/nodes/`
   are the one source of the artwork; this namespace only rewrites
   their text, so each runtime reads the files its own way (the server
   with `slurp`, the browser at compile time with
   `shadow.resource/inline`) and gets the same shapes.

   Every file draws its translucent interior with a `fill` +
   `fill-opacity` attribute pair, and its outline opaque. The canvas
   relies on that: the pair is the only layer a Colour tag recolours."
  (:require
   [clojure.string :as str]))

(defn symbol-id
  "Element id of a Part type's symbol. One id for both runtimes — the
   canvas and the Render never share a page."
  [type]
  (str "part-" (name type)))

(defn- strip-preamble
  [raw]
  (-> raw
      (str/replace #"<\?xml[^>]*\?>\s*" "")
      (str/replace #"<!DOCTYPE[^>]*>\s*" "")))

(defn ->symbol
  "The Part type's artwork as a plain `<symbol>`, colours as drawn: the
   outer `<svg …>` becomes `<symbol …>`, keeping its viewBox. Used by the
   Render, which must print every Part in its type's own look."
  [type raw]
  (let [svg      (strip-preamble raw)
        view-box (or (second (re-find #"viewBox=\"([^\"]+)\"" svg))
                     "0 0 100 100")]
    (-> svg
        (str/replace #"<svg[^>]*>"
                     (str "<symbol id=\"" (symbol-id type)
                          "\" viewBox=\"" view-box "\">"))
        (str/replace "</svg>" "</symbol>")
        str/trim)))

(defn ->tintable-symbol
  "The Part type's artwork as a `<symbol>` for the canvas. The interior
   layer reads its colour from the `--part-fill` / `--part-fill-opacity`
   CSS variables, falling back to the drawn values, so a Part with no
   Colour tag looks exactly as drawn. (Variables inherit into a `<use>`
   element's copy of the symbol; plain CSS selectors do not reach it.)
   Like the Render's symbol, it keeps the artwork's aspect ratio inside
   the Part's box — the canvas always has, when the files were CSS
   background images."
  [type raw]
  (-> (->symbol type raw)
      (str/replace #"fill=\"([^\"]*)\" fill-opacity=\"([^\"]*)\""
                   (str "style=\"fill:var(--part-fill, $1);"
                        "fill-opacity:var(--part-fill-opacity, $2)\""))))

;; The Unburdened aura: a soft pale-gold glow around a Part, drawn only
;; outside its outline. A mask cuts the Part's own silhouette out of the
;; glow, so no gold shows through the translucent fill. Every length is
;; a percentage of the Part's box, so the same markup works in a
;; node-sized <svg> on the canvas and in a Part-sized nested <svg> in
;; the Render.

(def aura-color
  "rgb(245,196,84)")

(def ^:private aura-radius-pct
  "The glow's radius, as a percentage of the Part's box (50 = its edge)."
  78)

(def aura-reach
  "How far the glow reaches past each side of the Part's box, as a
   fraction of the box's size. A renderer that crops to its content
   must leave this much room around an unburdened Part."
  (/ (- aura-radius-pct 50) 100.0))

(def aura-defs
  "Shared definitions every aura uses, for the page's one `<defs>`: the
   glow's radial gradient, and a filter that turns a shape into an
   opaque black silhouette (colour to 0, any alpha to 1) for the mask."
  (str "<radialGradient id=\"part-aura\">"
       "<stop offset=\"0.55\" stop-color=\"" aura-color "\" stop-opacity=\"0.6\"/>"
       "<stop offset=\"1\" stop-color=\"" aura-color "\" stop-opacity=\"0\"/>"
       "</radialGradient>"
       "<filter id=\"part-solid\">"
       "<feColorMatrix type=\"matrix\""
       " values=\"0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 100 0\"/>"
       "</filter>"))

(defn aura-markup
  "One Part's aura: its mask (id `mask-id`, unique on the page) and the
   masked glow. Draw it before the Part's shape. `xlink:href` because
   Batik does not read the SVG 2 `href`; browsers accept both."
  [mask-id type]
  (str "<mask id=\"" mask-id "\" maskUnits=\"userSpaceOnUse\""
       " x=\"-100%\" y=\"-100%\" width=\"300%\" height=\"300%\">"
       "<rect x=\"-100%\" y=\"-100%\" width=\"300%\" height=\"300%\" fill=\"#fff\"/>"
       "<use xlink:href=\"#" (symbol-id type) "\" width=\"100%\" height=\"100%\""
       " filter=\"url(#part-solid)\"/>"
       "</mask>"
       "<ellipse cx=\"50%\" cy=\"50%\""
       " rx=\"" aura-radius-pct "%\" ry=\"" aura-radius-pct "%\""
       " fill=\"url(#part-aura)\" mask=\"url(#" mask-id ")\"/>"))
