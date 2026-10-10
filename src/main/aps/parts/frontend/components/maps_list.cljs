(ns aps.parts.frontend.components.maps-list
  "Full-page Maps list route (/app). Fetches the list on mount and
   lets the user open or create a Map. Each Map is a card in a grid:
   the server-side SVG preview (see ADR-0008) on top, then the Map's
   title, a proportional part-type strip, and the latest Session plus
   the last-update time — all read from the list API's per-map :stats.
   Selecting a card navigates the client-side router to /app/maps/:id."
  (:require
   ["lucide-react/dist/esm/icons/ellipsis" :default Ellipsis]
   [aps.parts.common.constants :refer [part-colors part-labels
                                       part-toolbar-glyph part-type-order]]
   [aps.parts.common.utils :refer [plural]]
   [aps.parts.frontend.components.app-footer :refer [app-footer]]
   [aps.parts.frontend.components.app-header :refer [app-header]]
   [aps.parts.frontend.components.archive-confirmation :refer [archive-confirmation]]
   [aps.parts.frontend.components.banner :refer [banner]]
   [aps.parts.frontend.components.maps-list-view :as maps-view]
   [aps.parts.frontend.components.product-updates-view :as product-updates]
   [aps.parts.frontend.dates :as dates]
   [aps.parts.frontend.device :as device]
   [aps.parts.frontend.router :as router]
   [clojure.string :as str]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect use-layout-effect use-ref use-state]]
   [uix.re-frame :as uix.rf]))

(defn- title-matches?
  "Case-insensitive substring match against `the-map`'s title. `q` must
   arrive trimmed and lower-cased; a Map with no title matches nothing."
  [q the-map]
  (str/includes? (str/lower-case (or (:title the-map) "")) q))

(defui ^:private view-switch
  "Active | Archived [n], a macOS-style segmented control. The count is
   darker on the light (unselected) segment and lighter on the selected
   one, so it stays readable on both."
  [{:keys [view on-view archived-count]}]
  ($ :div {:class "join" :role "tablist" :aria-label "Maps to show"}
     ($ :button {:role          "tab"
                 :aria-selected (= view :active)
                 :class         (str "btn btn-sm join-item" (when (= view :active) " btn-active"))
                 :on-click      #(on-view :active)}
        "Active")
     ($ :button {:role          "tab"
                 :aria-selected (= view :archived)
                 :class         (str "btn btn-sm join-item gap-1.5" (when (= view :archived) " btn-active"))
                 :on-click      #(on-view :archived)}
        "Archived"
        ($ :span {:class (str "badge badge-xs border-0 "
                              (if (= view :archived)
                                "bg-base-100 text-base-content/70"
                                "bg-base-content/25 text-base-content"))}
           archived-count))))

(defui ^:private more-menu
  "The card's More (⋯) button and its menu, at the end of the card's
   bottom row, above the card's click area (z-10). The negative margin
   keeps the 24px button from making the 16px text row taller."
  [{:keys [archived? on-archive on-unarchive]}]
  ($ :div {:class "dropdown dropdown-end relative z-10 -my-1 shrink-0"}
     ($ :div {:tabIndex   0
              :role       "button"
              :aria-label "More"
              :class      "btn btn-xs btn-square bg-base-100 shadow-sm"}
        ($ Ellipsis {:size 14}))
     ($ :ul {:tabIndex 0
             :class    "dropdown-content menu menu-sm bg-base-100 rounded-box shadow-md w-44 p-1 mt-1"}
        ;; Blur first: daisyUI keeps the dropdown open while it has focus,
        ;; and Unarchive opens no dialog to take the focus away.
        ($ :li ($ :a {:on-click (fn []
                                  (.blur (.-activeElement js/document))
                                  ((if archived? on-unarchive on-archive)))}
                  (if archived? "Unarchive Map" "Archive Map…"))))))

(defn- type-count-label
  "\"3 Managers\" / \"1 Exile\" — count + type label, pluralised."
  [k n]
  (let [label (get-in part-labels [k :label])]
    (str n " " (plural n label (str label "s")))))

(defui ^:private type-strip
  "Proportional segmented bar of a Map's part types, in canonical order.
   The bar shows proportion; exact counts live in the hover tooltip."
  [{:keys [by-type]}]
  (let [present (filter #(pos? (get by-type % 0)) part-type-order)]
    (when (seq present)
      ($ :div {:class "tooltip block w-full"}
         ;; Tracks must be auto, not fr — fr tracks stretch the panel
         ;; far wider than its content despite w-max.
         ($ :div {:class "tooltip-content w-max text-xs"}
            ($ :div {:class "grid grid-cols-[auto_auto] gap-x-3 gap-y-1 p-1 text-left"}
               (for [k present]
                 ($ :div {:key   (name k)
                          :class "flex items-center gap-1.5 whitespace-nowrap"}
                    ($ :img {:src   (part-toolbar-glyph k)
                             :alt   ""
                             :class "h-3.5 w-auto"})
                    ($ :span (type-count-label k (get by-type k)))))))
         ($ :div {:class "flex h-1.5 rounded-full overflow-hidden gap-px"}
            (for [k present]
              ($ :div {:key   (name k)
                       :style {:flexGrow        (get by-type k)
                               :backgroundColor (part-colors k)}})))))))

(defui ^:private map-preview
  "The server-rendered SVG preview atop one Map's card (ADR-0008). Owns a
   `loaded?` flag so a *changed* preview shows a skeleton while its new
   image loads, without disturbing previews that didn't change."
  [{:keys [the-map]}]
  (let [;; `?v=` is a cache-bust fingerprint, not a server param — the handler
        ;; ignores it. As `:render_version` advances after an edit the Render
        ;; draws, the URL changes and the browser fetches fresh instead of
        ;; serving its cached copy.
        src                   (str "/api/maps/" (:id the-map) "/preview.svg"
                                   (when-let [^js u (:render_version the-map)]
                                     (str "?v=" (.getTime u))))
        [loaded? set-loaded!] (use-state false)
        img-ref               (use-ref nil)]
    ;; Before paint: a cached/unchanged preview is already `complete`, so we
    ;; skip the skeleton entirely (no flash on the previews that didn't move).
    ;; Only a new/uncached `src` — i.e. a Map you actually edited — stays
    ;; unloaded and shimmers. Keyed on `src` so a new fingerprint re-arms it.
    (use-layout-effect
     (fn []
       (when-let [^js img @img-ref]
         (set-loaded! (.-complete img)))
       js/undefined)
     [src])
    ($ :div {:class (str "relative w-full aspect-[4/3] bg-gray-50 "
                         "overflow-hidden rounded-t-lg "
                         "flex items-center justify-center border-b border-base-300")}
       ($ :img {:ref      img-ref
                :class    "max-w-full max-h-full object-contain p-3"
                :src      src
                :alt      (str "Preview of " (:title the-map))
                :on-load  #(set-loaded! true)
                ;; A broken image must resolve too, or it shimmers forever.
                :on-error #(set-loaded! true)})
       (when-not loaded?
         ($ :div {:class "absolute inset-0 skeleton"})))))

(defui ^:private map-card
  "One card in the Maps grid: the preview on top, then the title, the
   part-type strip, the latest Session with its anchor date, and a bottom
   row with the relative last-update time and the `menu`. An archived
   Map has a greyed preview and its archive date above the bottom row."
  [{:keys [the-map on-select menu]}]
  (let [{:keys [parts_by_type last_session]} (:stats the-map)
        archived-at                          (:archived_at the-map)]
    ;; The card frame is the wrapper. The button's ::after covers the
    ;; whole card, so a click anywhere opens the Map; the menu sits above
    ;; it, in the bottom row.
    ;; No overflow-hidden here — it would clip the strip's tooltip at the
    ;; card edges. The preview clips its own corners instead.
    ($ :div {:class (str "relative bg-white border border-base-300 rounded-lg "
                         "shadow-sm hover:shadow-md transition-shadow flex flex-col"
                         (when archived-at " [&_img]:grayscale [&_img]:opacity-60"))}
       ($ :button
          {:class    (str "cursor-pointer text-left p-0 flex flex-col w-full "
                          "after:absolute after:inset-0 after:content-['']")
           :on-click #(on-select the-map)}
          ($ map-preview {:the-map the-map})
          ($ :div {:class "p-3 pb-0 flex flex-col gap-1.5 w-full min-w-0"}
             ($ :h2 {:class "text-sm font-medium truncate"}
                (:title the-map))
             ($ type-strip {:by-type parts_by_type})
             (when last_session
               ($ :span {:class "flex items-baseline text-xs text-gray-500"}
                  ($ :span {:class "whitespace-nowrap"}
                     (str "Session " (:ordinal last_session)))
                  ($ :span {:class (str "flex-1 mx-1.5 self-center "
                                        "border-b border-dotted border-gray-300")})
                  ($ :span {:class "whitespace-nowrap"}
                     (dates/format-date dates/long-date-format
                                        (:anchor_valid_at last_session)))))))
       ($ :div {:class "px-3 pb-4 pt-3.5 mt-auto flex flex-col gap-0.5 text-xs text-gray-500"}
          (when archived-at
            ($ :span (str "Archived " (dates/format-date dates/long-date-format archived-at))))
          ($ :div {:class "flex items-center gap-2"}
             ($ :span {:class "flex-1 min-w-0 truncate"}
                (when-let [updated (dates/relative-past (:updated_at the-map))]
                  (str "Updated " updated)))
             menu)))))

;; Accounts from before 2026-10-10 are not subscribed to Product updates
;; until the User says yes. The question is a banner here, not a modal, so
;; it never interrupts work. It stays until the User answers. See ADR-0020.
(defui ^:private product-updates-question
  "The one-time question about Product updates, and a confirmation after
   the User subscribes."
  []
  (let [user                  (uix.rf/use-subscribe [:auth/user])
        [answered set-answer] (use-state nil)
        answer!               (fn [subscribe?]
                                (set-answer (if subscribe? :yes :no))
                                (rf/dispatch
                                 [:account/update
                                  {:product_updates subscribe?}]))]
    (case (product-updates/prompt user answered)
      :ask
      ($ banner {:variant :info :class "mb-4"}
         ($ :div {:class "flex flex-1 flex-wrap items-center gap-x-3 gap-y-2"}
            ($ :p {:class "flex-1 min-w-48"}
               ($ :span {:class "font-bold"} "Get product updates by email?")
               (str " We would like to occasionally email you about new"
                    " features added to Parts. You will always be able to"
                    " unsubscribe in one click."))
            ($ :div {:class "flex items-center gap-2 self-stretch sm:border-l sm:border-sky-200 sm:pl-3"}
               ($ :button {:class    "btn btn-sm"
                           :on-click #(answer! false)} "No, thanks")
               ($ :button {:class    "btn btn-sm btn-primary"
                           :on-click #(answer! true)} "Subscribe"))))

      :subscribed
      ($ banner {:variant :success :class "mb-4"}
         ($ :p "Subscribed. You can change this on your Account page."))

      nil)))

(defui maps-list []
  (let [maps                       (uix.rf/use-subscribe [:maps/list])
        loading                    (uix.rf/use-subscribe [:maps/loading])
        view                       (maps-view/view (uix.rf/use-subscribe [:router/query-params]))
        [query set-query]          (use-state "")
        [archiving set-archiving!] (use-state nil)
        archived-count             (count (filter :archived_at maps))
        in-view                    (maps-view/maps-in-view maps view)
        ;; Normalise the query once per render, not once per map.
        filtered                   (let [q (str/lower-case (str/trim query))]
                                     (if (str/blank? q)
                                       in-view
                                       (filter (partial title-matches? q) in-view)))
        show-view!                 (fn [v]
                                     (rf/dispatch [:router/navigate ::router/maps-list nil
                                                   (when (= v :archived) {:view "archived"})]))
        handle-create              (fn []
                                     (rf/dispatch [:map/create]))
        handle-select              (fn [the-map]
                                     (rf/dispatch [:router/navigate
                                                   ::router/map
                                                   {:id (:id the-map)}]))]

    ;; Fetch the list when the route mounts.
    (use-effect
     (fn []
       (rf/dispatch [:map/fetch-list])
       js/undefined)
     [])

    ;; Unarchiving the last archived Map hides the view switch, so the
    ;; list goes back to the active view rather than an empty one. Replace,
    ;; not push: Back must not return to the empty view.
    (use-effect
     (fn []
       (when (and (= view :archived) (seq maps) (zero? archived-count))
         (rf/dispatch [:router/replace ::router/maps-list]))
       js/undefined)
     [view maps archived-count])

    ($ :div {:class "min-h-screen bg-gray-50 p-4 flex flex-col"}
       ($ :div {:class "max-w-3xl mx-auto w-full flex flex-col flex-1"}
          ($ app-header)

          ;; Phones view, never edit (TASK-105) — creating a Map here
          ;; would only open a canvas with nothing to do on it, so this
          ;; banner replaces the Create button. Especially for a
          ;; zero-Maps account, which must not read as a dead end with
          ;; no explanation.
          (when device/phone-primary?
            ($ banner {:variant :warning :class "mb-4"}
               ($ :p "To create and edit Maps, use a tablet or computer.")))

          ($ product-updates-question)

          ($ :div {:class "flex flex-wrap items-center justify-between gap-3 mb-4"}
             ($ :div {:class "flex items-center gap-3"}
                ($ :h1 {:class "text-lg font-bold"} "Your Maps")
                ;; Shown once a Map is archived: archived Maps leave the
                ;; grid, and this is where they are.
                (when (pos? archived-count)
                  ($ view-switch {:view           view
                                  :on-view        show-view!
                                  :archived-count archived-count})))
             ;; w-full below sm gives the input's w-full a real width to
             ;; resolve against — a shrink-wrapped flex parent would make
             ;; the percentage a no-op.
             ($ :div {:class "flex flex-wrap items-center gap-2 w-full sm:w-auto"}
                (when-not device/phone-primary?
                  ($ :button
                     {:class    "btn btn-sm btn-primary"
                      :on-click handle-create}
                     "Create a new Map"))
                ;; Hidden on an account with no Maps at all. It depends on
                ;; every Map, not the view, so switching views never shows
                ;; or hides it; it stays put during a background refresh.
                (when (seq maps)
                  ($ :input {:type        "search"
                             :placeholder "Filter by title"
                             :class       "input input-bordered input-sm w-full sm:w-56"
                             :value       query
                             :on-change   #(set-query (.. % -target -value))}))))

          (cond
            ;; Full spinner only on the FIRST load (nothing to show yet). A
            ;; background refresh with maps already in hand falls through and
            ;; keeps rendering the existing list — stale-while-revalidate.
            (and loading (empty? maps))
            ($ :div {:class "flex justify-center py-12"}
               ($ :div {:class "loading loading-spinner"}))

            (empty? maps)
            ($ :p {:class "text-center py-12 text-gray-500"}
               "No Maps yet")

            (and (empty? in-view) (= view :active))
            ($ :p {:class "text-center py-12 text-gray-500"}
               "All your Maps are archived.")

            (empty? filtered)
            ($ :p {:class "text-center py-12 text-gray-500"}
               "No Maps match \"" query "\"")

            :else
            ($ :div {:class "grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4"}
               (for [the-map filtered]
                 ($ map-card {:key       (:id the-map)
                              :the-map   the-map
                              :on-select handle-select
                              :menu      (when-not device/phone-primary?
                                           ($ more-menu
                                              {:archived?    (some? (:archived_at the-map))
                                               :on-archive   #(set-archiving! the-map)
                                               :on-unarchive #(rf/dispatch [:map/set-archived (:id the-map) false])}))}))))

          ($ app-footer)

          ($ archive-confirmation {:the-map    archiving
                                   :on-close   #(set-archiving! nil)
                                   :on-confirm (fn []
                                                 (rf/dispatch [:map/set-archived (:id archiving) true])
                                                 (set-archiving! nil))})))))
