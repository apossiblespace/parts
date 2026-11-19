(ns aps.parts.handlers.pages
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.config :as config]
   [aps.parts.views.layouts :as layouts]
   [aps.parts.views.partials :as partials]
   [hiccup2.core :refer [html]]
   [ring.util.response :as response]))

(defn map-graph
  "Page rendering the graph of a map"
  [{:keys [demo-mode]}]
  (let [demo-mode (or demo-mode false)]
    (response/response
     (html
      (layouts/fullscreen
       {:title      "Map"
        :analytics? true
        :styles     ["/css/flow.css" "/css/style.css"]}
       [:div#root {:data-demo-mode demo-mode}])))))

(defn playground
  "Page rendering a playground map graph in demo mode"
  [_]
  (map-graph {:demo-mode "true"}))

(defn app-shell
  "Server-rendered shell for the React SPA mounted under /app.
   Renders an empty #root that the client-side router fills in. No
   data-demo-mode — this is the real app, not the playground demo. The
   client router matches the URL; the catch-all route makes deep links
   survive a refresh."
  [_]
  (response/response
   (html
    (layouts/fullscreen
     {:title  "Map"
      :styles ["/css/flow.css" "/css/style.css"]}
     [:div#root]))))

(defn- feature-video
  "Muted looping clip for a feature card. Video, not GIF: same frames at
   about a tenth of the bytes."
  [name label]
  [:video.rounded-lg.mb-4
   {:autoplay    true
    :loop        true
    :muted       true
    :playsinline true
    :preload     "metadata"
    :aria-label  label}
   [:source {:src (str "/images/home/" name ".webm") :type "video/webm"}]
   [:source {:src (str "/images/home/" name ".mp4") :type "video/mp4"}]])

(defn- hero
  "Landing hero, responsive at the lg breakpoint.

   lg and up: the interactive demo map is a full-bleed background that stays
   usable, with the headline/sub-head/CTAs overlaid on the left — each with a
   white highlight behind the text itself (box-decoration-clone) so the canvas
   shows through. The overlay is click-through except for the CTAs, and the
   map's initial viewport + toolbar are nudged right (see the `minimal` branch
   in map.cljs) so the text only ever covers empty canvas.

   Below lg: the demo is hidden and the hero is just text on the page
   background. The highlight and click-through are lg-only to match."
  []
  ;; The white text highlight only makes sense over the canvas (lg overlay);
  ;; on mobile the text sits on the page background, so keep it plain.
  (let [highlight ["lg:box-decoration-clone" "lg:bg-white" "lg:px-3" "lg:py-1"]]
    [:section {:class ["relative" "overflow-hidden" "lg:h-[750px]"]}
     ;; Text first in the DOM so it stacks above the demo on mobile; on lg the
     ;; demo is taken out of flow (absolute) and this overlays it.
     [:div {:class ["relative" "z-10" "lg:pointer-events-none" "mx-auto"
                    "max-w-7xl" "container" "px-4" "sm:px-6" "lg:px-8"
                    "pt-32" "pb-12" "lg:pb-20"]}
      ;; Re-enable pointer events on the text column so it's selectable (the
      ;; container is click-through at lg so empty canvas still pans).
      [:div {:class ["max-w-xl" "lg:pointer-events-auto"]}
       ;; leading-tight is the floor that keeps the box-decoration-clone
       ;; highlight from overlapping line-to-line and clipping the glyphs.
       ;; -ml-3 on the block (not the inline span) pulls every wrapped line left
       ;; by the highlight's px-3 so the glyphs align with the logo/container.
       [:h1 {:class ["text-5xl" "md:text-6xl" "font-bold" "leading-tight" "lg:-ml-3"]}
        [:span {:class highlight}
         "Understand your clients’ parts and their relationships."]]
       [:h3 {:class ["my-8" "text-xl" "lg:-ml-3"]}
        [:span {:class highlight}
         [:strong.font-bold "Parts"]
         " is a mapping tool for IFS™ practitioners to keep track of, visualise, and explore the relationships between their clients’ parts, across sessions."]]
       [:div {:class ["grid" "grid-cols-1" "sm:grid-cols-2" "gap-2"
                      "max-w-lg" "pointer-events-auto"]}
        [:a.btn.btn-primary.btn-lg.hover:bg-opacity-90.transform.hover:scale-105.transition.duration-200
         {:role                  "button"
          :href                  "/app/signup"
          :data-analytics        "Create Account Click"
          :data-analytics-source "homepage-hero"}
         "Create an account"]
        [:a.btn.btn-lg {:role "button"
                        :href "/playground"}
         "Expand the playground"]]]]
     ;; The demo: hidden on mobile; a full-bleed background behind the text on lg.
     [:div#root {:data-demo-mode "minimal"
                 :class          ["demo" "minimal" "bg-white"
                                  "hidden" "lg:block"
                                  "lg:absolute" "lg:inset-0"]}]]))

(def ^:private eu-stars-path
  "SVG path data for a round EU icon: a disc with twelve star cut-outs."
  (str "M1 12a11 11 0 1 0 22 0a11 11 0 1 0-22 0Z"
       "M12 6.20 12.22 6.89 12.95 6.89 12.36 7.32 12.59 8.01 12 7.58 11.41 8.01 11.64 7.32 11.05 6.89 11.78 6.89Z"
       "M14.40 6.84 14.62 7.53 15.35 7.53 14.76 7.96 14.99 8.65 14.40 8.23 13.81 8.65 14.04 7.96 13.45 7.53 14.18 7.53Z"
       "M16.16 8.60 16.38 9.29 17.11 9.29 16.52 9.72 16.74 10.41 16.16 9.98 15.57 10.41 15.79 9.72 15.21 9.29 15.93 9.29Z"
       "M16.80 11 17.02 11.69 17.75 11.69 17.16 12.12 17.39 12.81 16.80 12.38 16.21 12.81 16.44 12.12 15.85 11.69 16.58 11.69Z"
       "M16.16 13.40 16.38 14.09 17.11 14.09 16.52 14.52 16.74 15.21 16.16 14.78 15.57 15.21 15.79 14.52 15.21 14.09 15.93 14.09Z"
       "M14.40 15.16 14.62 15.85 15.35 15.85 14.76 16.27 14.99 16.97 14.40 16.54 13.81 16.97 14.04 16.27 13.45 15.85 14.18 15.85Z"
       "M12 15.80 12.22 16.49 12.95 16.49 12.36 16.92 12.59 17.61 12 17.18 11.41 17.61 11.64 16.92 11.05 16.49 11.78 16.49Z"
       "M9.60 15.16 9.82 15.85 10.55 15.85 9.96 16.27 10.19 16.97 9.60 16.54 9.01 16.97 9.24 16.27 8.65 15.85 9.38 15.85Z"
       "M7.84 13.40 8.07 14.09 8.79 14.09 8.21 14.52 8.43 15.21 7.84 14.78 7.26 15.21 7.48 14.52 6.89 14.09 7.62 14.09Z"
       "M7.20 11 7.42 11.69 8.15 11.69 7.56 12.12 7.79 12.81 7.20 12.38 6.61 12.81 6.84 12.12 6.25 11.69 6.98 11.69Z"
       "M7.84 8.60 8.07 9.29 8.79 9.29 8.21 9.72 8.43 10.41 7.84 9.98 7.26 10.41 7.48 9.72 6.89 9.29 7.62 9.29Z"
       "M9.60 6.84 9.82 7.53 10.55 7.53 9.96 7.96 10.19 8.65 9.60 8.23 9.01 8.65 9.24 7.96 8.65 7.53 9.38 7.53Z"))

(def ^:private safety-icon-fill "#3a9070")
(def ^:private safety-background "#eaf6f1")

(defn- safety-icon
  "Returns a round icon with the line `shapes` drawn inside it.
   Each shape is a Hiccup SVG element on a 24 by 24 grid."
  [& shapes]
  [:svg {:width 60 :height 60 :viewBox "0 0 24 24" :aria-hidden "true"}
   [:circle {:cx 12 :cy 12 :r 11 :fill safety-icon-fill}]
   (into [:g {:transform       "translate(6 6) scale(0.5)"
              :fill            "none"
              :stroke          safety-background
              :stroke-width    2
              :stroke-linecap  "round"
              :stroke-linejoin "round"}]
         ;; The stroke width is in screen pixels, so the scale above and
         ;; the icon size do not change it.
         (map #(update % 1 assoc :vector-effect "non-scaling-stroke"))
         shapes)])

(defn- safety-point
  [icon title text]
  [:li {:class ["flex" "flex-col" "items-start" "gap-4"]}
   icon
   [:div
    [:h3.text-lg.font-bold.mb-1 title]
    [:p.text-gray-600 text]]])

(defn- data-safety-section
  []
  [:section#data-safety.py-20.border-b-1.border-dashed
   {:style {:background-color safety-background
            :border-color     safety-icon-fill}}
   [:div.container.max-w-7xl.mx-auto.px-4.sm:px-6.lg:px-8
    [:h2.text-3xl.font-bold.mb-2 "Keeping your clients’ data safe"]
    [:p.text-gray-600.mb-12 "Mental health-related personal data is highly sensitive, and we treat it accordingly."]
    [:ul {:class ["grid" "gap-10" "grid-cols-1" "sm:grid-cols-2" "lg:grid-cols-4"]}
     (safety-point
      (safety-icon [:path {:d "M9 9v3a3 3 0 0 0 5.1 2.1"}]
                   [:path {:d "M15 9.3V5a3 3 0 0 0-5.7-1.3"}]
                   [:path {:d "M19 11a7 7 0 0 1-1.2 3.9"}]
                   [:path {:d "M5 11a7 7 0 0 0 11 5.7"}]
                   [:path {:d "M12 18v3"}]
                   [:path {:d "M3 3l18 18"}])
      "No recordings, no AI"
      "Parts does not record your sessions with clients. Your clients’ data is never fed to an AI provider.")
     (safety-point
      [:svg {:width 60 :height 60 :viewBox "0 0 24 24" :aria-hidden "true"}
       [:path {:d eu-stars-path :fill safety-icon-fill :fill-rule "evenodd"}]]
      "EU data residency"
      "We store and process your clients’ data in the European Union (Germany and France). It never goes to a US company.")
     (safety-point
      (safety-icon [:rect {:x 4 :y 11 :width 16 :height 10 :rx 2}]
                   [:path {:d "M8 11V7a4 4 0 0 1 8 0v4"}]
                   [:path {:d "M12 15v2"}])
      "Encryption"
      "Your clients’ data is encrypted in transit and on disk, including in backups.")
     (safety-point
      (safety-icon [:rect {:x 3 :y 4 :width 18 :height 4 :rx 1}]
                   [:path {:d "M5 8v11a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8"}]
                   [:path {:d "M12 17.5v-6"}]
                   [:path {:d "M9.5 14L12 11.5 14.5 14"}])
      "Your data stays yours"
      "You are never locked in: all the information you have input into Parts can be exported at any time.")]
    [:p.mt-12.text-sm.text-gray-600
     "Parts is built by "
     (partials/text-link "https://a.possible.space" "A Possible Space Ltd")
     ", an "
     (partials/text-link "https://ico.org.uk/ESDWebPages/Entry/ZC166893"
                         "ICO registered")
     " UK company. Read more about how we protect your data in our "
     (partials/text-link "/privacy" "privacy policy")
     "."]]])

(defn- landing-page
  []
  (response/response
   (html
    (layouts/marketing
     {:title  nil
      :styles ["/css/flow.css" "/css/style.css"]}
     (hero)
     [:section#features.text-white
      {:style {:background-color "#4eb48a"}}
      [:div.container.max-w-7xl.mx-auto.py-8.px-4.sm:px-6.lg:px-8
       [:h2.text-4xl.font-bold.mb-12.mt-8 "Built for the work between sessions."]
       (let [card-class "aspect-square flex flex-col"]
         [:ul.grid.gap-12.grid-cols-1.md:grid-cols-2.lg:grid-cols-3
          [:li {:class card-class}
           (feature-video "parts" "A Manager part being placed on the map")
           [:h3.text-lg.font-bold "Map your clients’ Parts"]
           [:p
            "Place Managers, Firefighters, and Exiles on a freeform canvas and arrange them as the work progresses."]]
          [:li {:class card-class}
           (feature-video "relationships" "A relationship being drawn between two parts")
           [:h3.text-lg.font-bold "Define the relationships"]
           [:p
            "Connect Parts with relationships of varying intensities"]]
          [:li {:class card-class}
           (feature-video "timetravel" "Stepping back through past sessions of a map")
           [:h3.text-lg.font-bold "Time Travel"]
           [:p
            "Explore a Map’s evolution across sessions with our Time Travel tool"]]
          [:li {:class card-class}
           (feature-video "notes" "Adding a note to a part")
           [:h3.text-lg.font-bold "Capture clinical detail"]
           [:p
            "Keep notes on Parts and relationships, including where in the body a Part is felt"]]
          [:li {:class card-class}
           (feature-video "pdf" "Exporting a map as a PDF")
           [:h3.text-lg.font-bold "Share a client’s map with them"]
           [:p
            "A Map can be downloaded as a PDF to share with your client, as homework support or for shared context"]]
          [:li {:class card-class}
           [:div {:class       "aspect-[8/5] rounded-lg mb-4 border-2 border-dashed border-white/60 flex items-center justify-center"
                  :aria-hidden true}
            [:span.text-6xl.font-light "+"]]
           [:h3.text-lg.font-bold "And more coming soon"]
           [:p
            "Parts is in active development, shaped by the practitioners who use it"]]])]]
     (data-safety-section)
     [:section.py-20.bg-gray-100
      [:div.container.max-w-5xl.mx-auto.px-4.sm:px-6.lg:px-8
       [:iframe.w-full.rounded-lg.shadow-lg
        {:class           "aspect-[16/10]"
         :src             (str "https://www.youtube-nocookie.com/embed/" (config/walkthrough-video-id))
         :title           "Parts demonstration video"
         :loading         "lazy"
         :allow           "encrypted-media; fullscreen; picture-in-picture"
         :referrerpolicy  "strict-origin-when-cross-origin"
         :allowfullscreen true}]]]
     (partials/pricing-section)
     [:section.py-16
      [:div.container.max-w-7xl.mx-auto.px-4.sm:px-6.lg:px-8
       [:h2.text-3xl.font-bold.text-center.mb-12
        "Who made this?"]
       [:div.grid.grid-cols-1.md:grid-cols-2.gap-12.max-w-4xl.mx-auto
        [:div.flex
         [:img.w-20.h-20.rounded-full
          {:src "/images/avatars/gosha.svg"
           :alt "Gosha Tcherednitchenko"}]
         [:div.ml-6
          [:h3.text-xl.font-semibold "Gosha Tcherednitchenko"]
          [:p.text-gray-600.mt-1
           "Software engineer with 20 years experience building for the Web."]
          [:div.mt-2
           [:a.text-ifs-green.mr-3
            {:href "https://gosha.net"}
            "Website"]]]]
        [:div.flex
         [:img.w-20.h-20.rounded-full
          {:src "/images/avatars/tingyi.svg"
           :alt "Ting-yi Lai"}]
         [:div.ml-6
          [:h3.text-xl.font-semibold "Ting-yi Lai"]
          [:p.text-gray-600.mt-1
           "IFS™ Level 1 trained art psychotherapist, focusing on trauma."]
          [:div.mt-2
           [:a.text-ifs-yellow
            {:href "https://tingyilai.com"}
            "Website"]]]]]]]))))

(defn home-page
  "Page rendered for GET /. Logged-in users are redirected into the app."
  [request]
  (if (auth/current-user-id request)
    (response/redirect "/app")
    (landing-page)))
