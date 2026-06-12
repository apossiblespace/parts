(ns aps.parts.common.constants)

(def part-labels
  {:unknown     {:label "Unknown"}
   :manager     {:label "Manager"}
   :firefighter {:label "Firefighter"}
   :exile       {:label "Exile"}})

(def part-types
  (set (map name (keys part-labels))))

(def part-min-size
  "Canvas resize bounds for a Part's width/height, in Map coordinates.
   Enforced twice: in the canvas resizer's props and at the model's spec
   gate, so a malformed change-event can't write absurd dimensions. At the
   minimum, the interior still keeps a usable move-drag surface after the
   connect ring takes its clamped share (ADR-0011)."
  60)

(def part-max-size 400)

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

(def support-email
  "The monitored concierge-support address. Shared FE/BE: the maps-list
   footer (cljs), the marketing footer (hiccup), and the invite-error page
   (hiccup) all render it as a mailto link."
  "help@ifs.tools")

(def legal-documents
  "The legal documents the app serves, in nav order. Shared FE/BE: the document
   layout's header/footer (hiccup), the marketing footer (hiccup), and the
   maps-list footer (cljs) all render these links from here; the legal loader
   derives its valid-slug set from this list. Each path is \"/\" + slug."
  [{:slug "privacy" :label "Privacy Policy" :mini-label "Privacy"}
   {:slug "terms" :label "Terms of Service" :mini-label "Terms"}
   {:slug "dpa" :label "Data Processing Agreement" :mini-label "Data"}])

(def medical-data-notice
  "Label for the onboarding medical-data acknowledgement checkbox. A required
   gate, not a recorded acceptance: the Privacy Policy (which discloses this
   processing) is the versioned record, and the private-pay scoping is a Terms
   of Service warranty accepted via the legal-documents checkbox (see ADR-0009)."
  "I understand that mental-health information is processed in Parts, as described in the Privacy Policy.")
