(ns aps.parts.handlers.pages
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.handlers.waitlist :refer [signups-count]]
   [aps.parts.launch :as launch]
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
       [:div#root {:data-demo-mode demo-mode
                   :data-launched  (str (launch/launched?))}])))))

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
     [:div#root {:data-launched (str (launch/launched?))}]))))

(defn- hero
  "Landing hero, responsive at the lg breakpoint.

   lg and up: the interactive demo map is a full-bleed background that stays
   usable, with the headline/sub-head/CTAs overlaid on the left — each with a
   white highlight behind the text itself (box-decoration-clone) so the canvas
   shows through. The overlay is click-through except for the CTAs, and the
   map's initial viewport + toolbar are nudged right (see the `minimal` branch
   in map.cljs) so the text only ever covers empty canvas.

   Below lg: the demo is hidden and the hero is just text on the page
   background. The highlight and click-through are lg-only to match.

   `cta` is the call-to-action markup, which differs per launch state."
  [{:keys [waitlist-count cta]}]
  ;; The white text highlight only makes sense over the canvas (lg overlay);
  ;; on mobile the text sits on the page background, so keep it plain.
  (let [highlight ["lg:box-decoration-clone" "lg:bg-white" "lg:px-3" "lg:py-1"]]
    [:section {:class ["relative" "overflow-hidden" "lg:h-[90vh]"]}
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
         " is a mapping tool for IFS practitioners to keep track of, visualise, and explore the relationships between their clients’ parts."]]
       [:div {:class ["grid" "grid-cols-1" "sm:grid-cols-2" "gap-2"
                      "max-w-lg" "pointer-events-auto"]}
        cta]
       [:p {:class ["mt-6" "text-sm" "lg:-ml-3"]}
        [:span {:class (conj highlight "text-gray-500")}
         "Current founding members: "
         [:span#counter waitlist-count]
         " practitioners."]]]]
     ;; The demo: hidden on mobile; a full-bleed background behind the text on lg.
     [:div#root {:data-demo-mode "minimal"
                 :class          ["demo" "minimal" "bg-white"
                                  "hidden" "lg:block"
                                  "lg:absolute" "lg:inset-0"]}]]))

(defn home-page-signup
  "Post-launch landing page: header and hero CTAs link to the /app SPA."
  [_]
  (let [waitlist-count (signups-count)]
    (response/response
     (html
      (layouts/marketing
       {:title  nil
        :styles ["/css/flow.css" "/css/style.css"]}
       (hero
        {:waitlist-count waitlist-count
         :cta            (list
                          [:a.btn.btn-primary.btn-lg.hover:bg-opacity-90.transform.hover:scale-105.transition.duration-200
                           {:role    "button"
                            :href    "/app/signup"
                            :onclick "plausible('Create Account Click', {props: {source: 'homepage-hero'}}); return true;"}
                           "Create an account"]
                          [:a.btn.btn-lg {:role "button"
                                          :href "/playground"}
                           "Expand the playground"])})
       [:section#signup.py-20.text-white
        {:style {:background-color "#4eb48a"}}
        [:div.container.max-w-7xl.mx-auto.px-4.sm:px-6.lg:px-8
         [:h2.text-3xl.font-bold.mb-6.text-center
          "Join the Founding Practitioners Circle"]
         [:p.text-xl.mb-8.text-center
          "Parts is being actively developed — be among the first IFS practitioners to help shape its future."]
         [:div.grid.grid-cols-1.md:grid-cols-3.gap-4.md:gap-8.max-w-5xl.mx-auto.my-12
          [:div.flex
           [:img.w-20.h-20
            {:src "/images/icons/build.png"
             :alt "An icon representing a toolbox with some tools in it"}]
           [:div.ml-4
            [:h3.text-lg.font-bold "Help shape Parts"]
            [:p
             "Your feedback will make Parts better for clients & therapists"]]]
          [:div.flex
           [:img.w-20.h-20
            {:src "/images/icons/key.png"
             :alt "An icon representing a key on a keychain"}]
           [:div.ml-4
            [:h3.text-lg.font-bold "Early access"]
            [:p
             "Start using Parts and new features before general availability"]]]
          [:div.flex
           [:img.w-20.h-20
            {:src "/images/icons/concierge.png"
             :alt "An icon representing a concierge's bell"}]
           [:div.ml-4
            [:h3.text-lg.font-bold "Concierge support"]
            [:p
             "Help getting setup and started, straight from the developer"]]]]
         [:div.mx-auto.text-center
          (partials/waitlist-signup-form {})]]]
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
             "IFS Level 1 trained art psychotherapist, focusing on trauma."]
            [:div.mt-2
             [:a.text-ifs-yellow
              {:href "https://tingyilai.com"}
              "Website"]]]]]]])))))

(defn home-page-waitlist
  "Pre-launch landing page: hero CTA anchors down to the waitlist email form."
  [_]
  (let [waitlist-count (signups-count)]
    (response/response
     (html
      (layouts/marketing
       {:title  nil
        :styles ["/css/flow.css" "/css/style.css"]}
       (hero
        {:waitlist-count waitlist-count
         :cta            (list
                          [:a.btn.btn-primary.btn-lg.hover:bg-opacity-90.transform.hover:scale-105.transition.duration-200
                           {:role "button"
                            :href "#signup"}
                           "Join the Founding Circle"]
                          [:a.btn.btn-lg {:role "button"
                                          :href "/playground"}
                           "Expand the playground"])})
       [:section#signup.py-20.text-white
        {:style {:background-color "#4eb48a"}}
        [:div.container.max-w-7xl.mx-auto.px-4.sm:px-6.lg:px-8
         [:h2.text-3xl.font-bold.mb-6.text-center
          "Join the Founding Practitioners Circle"]
         [:p.text-xl.mb-8.text-center
          "Parts is being actively developed — be among the first IFS practitioners to help shape its future."]
         [:div.grid.grid-cols-1.md:grid-cols-3.gap-4.md:gap-8.max-w-5xl.mx-auto.my-12
          [:div.flex
           [:img.w-20.h-20
            {:src "/images/icons/build.png"
             :alt "An icon representing a toolbox with some tools in it"}]
           [:div.ml-4
            [:h3.text-lg.font-bold "Help shape Parts"]
            [:p
             "Your feedback will make Parts better for clients & therapists"]]]
          [:div.flex
           [:img.w-20.h-20
            {:src "/images/icons/key.png"
             :alt "An icon representing a key on a keychain"}]
           [:div.ml-4
            [:h3.text-lg.font-bold "Early access"]
            [:p
             "Start using Parts and new features before general availability"]]]
          [:div.flex
           [:img.w-20.h-20
            {:src "/images/icons/concierge.png"
             :alt "An icon representing a concierge's bell"}]
           [:div.ml-4
            [:h3.text-lg.font-bold "Concierge support"]
            [:p
             "Help getting setup and started, straight from the developer"]]]]
         [:div.mx-auto.text-center
          (partials/waitlist-signup-form {})]]]
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
             "IFS Level 1 trained art psychotherapist, focusing on trauma."]
            [:div.mt-2
             [:a.text-ifs-yellow
              {:href "https://tingyilai.com"}
              "Website"]]]]]]])))))

(defn home-page
  "Page rendered for GET /. Logged-in users are redirected into the app;
   otherwise picks the signup or waitlist variant from the runtime launch
   toggle (see `aps.parts.launch`)."
  [request]
  (if (auth/current-user-id request)
    (response/redirect "/app")
    (if (launch/launched?)
      (home-page-signup request)
      (home-page-waitlist request))))
