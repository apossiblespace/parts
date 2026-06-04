(ns aps.parts.common.constants)

(def part-labels
  {:unknown     {:label "Unknown"}
   :manager     {:label "Manager"}
   :firefighter {:label "Firefighter"}
   :exile       {:label "Exile"}})

(def part-types
  (set (map name (keys part-labels))))

(def relationship-labels
  {:unknown      {:label "Unknown"}
   :protective   {:label "Protective"}
   :polarization {:label "Polarisation"}
   :alliance     {:label "Alliance"}
   :burden       {:label "Burden"}
   :blended      {:label "Blended"}})

(def relationship-types
  (set (map name (keys relationship-labels))))

(def relationship-colors
  "Per-Relationship-type stroke colour. Mirrors the `--edge-color-*` CSS
   custom properties in `resources/styles/main.css` — an SVG generated
   server-side cannot read CSS custom properties, so the palette lives
   in two places. The cross-runtime drift is caught at manual review
   (ADR-0008). All Clojure consumers (document renderer, preview
   renderer's `:unknown`, anywhere else) read from here."
  {:unknown      "#999999"
   :protective   "#4caf50"
   :polarization "#f44336"
   :alliance     "#2196f3"
   :burden       "#ff9800"
   :blended      "#9c27b0"})

(def brand-suffix
  "The suffix appearing after the page title in the <title> element"
  "Parts: IFS parts mapping for therapists and their clients")

(def legal-documents
  "The legal documents the app serves, in nav order. Shared FE/BE: the document
   layout's header/footer (hiccup), the marketing footer (hiccup), and the
   maps-list footer (cljs) all render these links from here; the legal loader
   derives its valid-slug set from this list. Each path is \"/\" + slug."
  [{:slug "privacy" :label "Privacy Policy" :mini-label "Privacy"}
   {:slug "terms" :label "Terms of Service" :mini-label "Terms"}
   {:slug "dpa" :label "Data Processing Agreement" :mini-label "Data"}])
