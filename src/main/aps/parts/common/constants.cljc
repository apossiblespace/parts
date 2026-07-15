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

(def part-default-size
  "Fallback width/height for Parts stored before size was recorded —
   the DB default for legacy rows."
  100)

(def relationship-type-order
  "Relationship types in canonical display order. Menus render from this
   vector rather than relying on map ordering."
  [:unknown :protects :polarizes-with :works-with :activates :carries-burden
   :fearful-of :suppresses])

(def relationship-labels
  "Keyword → UI label for Relationship types. Keywords use US spelling,
   labels British English (the project spelling convention)."
  {:unknown        {:label "Unknown"}
   :protects       {:label "Protects"}
   :polarizes-with {:label "Polarises with"}
   :works-with     {:label "Works with"}
   :activates      {:label "Activates"}
   :carries-burden {:label "Carries burden"}
   :fearful-of     {:label "Fearful of"}
   :suppresses     {:label "Suppresses"}})

(def relationship-types
  (set (map name relationship-type-order)))

(defn relationship-edge-label
  "The label drawn along an edge of `type` (string or keyword), or nil
   for unknown — a grey line already reads as unknown, so labelling it
   is noise. Menus and forms that legitimately want the word
   \"Unknown\" read `relationship-labels` directly."
  [type]
  (let [k (keyword type)]
    (when-not (= k :unknown)
      (get-in relationship-labels [k :label]))))

(def relationship-colors
  "Per-Relationship-type stroke colour. Mirrors the `--edge-color-*` CSS
   custom properties in `resources/styles/main.css` — an SVG generated
   server-side cannot read CSS custom properties, so the palette lives
   in two places. The architecture-fitness test checks the CSS side
   covers every type with matching values (ADR-0008). All Clojure
   consumers (document renderer, preview
   renderer's `:unknown`, anywhere else) read from here."
  {:unknown        "#999999"
   :protects       "#4caf50"
   :polarizes-with "#f44336"
   :works-with     "#2196f3"
   :activates      "#9c27b0"
   :carries-burden "#ff9800"
   :fearful-of     "#009688"
   :suppresses     "#795548"})

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
