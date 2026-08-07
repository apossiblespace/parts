(ns aps.parts.frontend.components.maps-list
  "Full-page Maps list route (/app/maps). Fetches the list on mount and
   lets the user open or create a Map. Each Map is a card in a grid:
   the server-side SVG preview (see ADR-0008) on top, then the Map's
   title, a proportional part-type strip, and the latest Session plus
   the last-update time — all read from the list API's per-map :stats.
   Selecting a card navigates the client-side router to /app/maps/:id."
  (:require
   [aps.parts.common.constants :refer [part-colors part-labels
                                       part-toolbar-glyph part-type-order]]
   [aps.parts.common.utils :refer [plural]]
   [aps.parts.frontend.components.app-footer :refer [app-footer]]
   [aps.parts.frontend.components.app-header :refer [app-header]]
   [aps.parts.frontend.components.banner :refer [banner]]
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
        ;; ignores it. As `:updated_at` advances after an edit, the URL changes
        ;; and the browser fetches fresh instead of serving its cached copy.
        src                   (str "/api/maps/" (:id the-map) "/preview.svg"
                                   (when-let [^js u (:updated_at the-map)]
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
   part-type strip, and a two-line footer — the latest Session with its
   anchor date, and the relative last-update time."
  [{:keys [the-map on-select]}]
  (let [{:keys [parts_by_type last_session]} (:stats the-map)]
    ($ :button
       ;; No overflow-hidden here — it would clip the strip's tooltip at
       ;; the card edges. The preview clips its own corners instead.
       {:class    (str "cursor-pointer bg-white border border-base-300 rounded-lg "
                       "shadow-sm hover:shadow-md transition-shadow "
                       "text-left p-0 flex flex-col")
        :on-click #(on-select the-map)}
       ($ map-preview {:the-map the-map})
       ($ :div {:class "p-3 flex flex-col gap-1.5 w-full min-w-0"}
          ($ :h2 {:class "text-sm font-medium truncate"}
             (:title the-map))
          ($ type-strip {:by-type parts_by_type})
          ($ :div {:class "text-xs text-gray-500 flex flex-col gap-0.5"}
             (when last_session
               ($ :span {:class "flex items-baseline"}
                  ($ :span {:class "whitespace-nowrap"}
                     (str "Session " (:ordinal last_session)))
                  ($ :span {:class (str "flex-1 mx-1.5 self-center "
                                        "border-b border-dotted border-gray-300")})
                  ($ :span {:class "whitespace-nowrap"}
                     (dates/format-date dates/long-date-format
                                        (:anchor_valid_at last_session)))))
             (when-let [updated (dates/relative-past (:updated_at the-map))]
               ($ :span {:class "mt-3"}
                  (str "Updated " updated))))))))

(defui maps-list []
  (let [maps              (uix.rf/use-subscribe [:maps/list])
        loading           (uix.rf/use-subscribe [:maps/loading])
        [query set-query] (use-state "")
        ;; Normalise the query once per render, not once per map.
        filtered          (let [q (str/lower-case (str/trim query))]
                            (if (str/blank? q)
                              maps
                              (filter (partial title-matches? q) maps)))

        handle-create     (fn []
                            (rf/dispatch [:map/create]))

        handle-select     (fn [the-map]
                            (rf/dispatch [:router/navigate
                                          ::router/map
                                          {:id (:id the-map)}]))]

    ;; Fetch the list when the route mounts.
    (use-effect
     (fn []
       (rf/dispatch [:map/fetch-list])
       js/undefined)
     [])

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

          ($ :div {:class "flex flex-wrap items-center justify-between gap-3 mb-4"}
             ($ :h1 {:class "text-lg font-bold"} "Your Maps")
             ;; w-full below sm gives the input's w-full a real width to
             ;; resolve against — a shrink-wrapped flex parent would make
             ;; the percentage a no-op.
             ($ :div {:class "flex flex-wrap items-center gap-2 w-full sm:w-auto"}
                (when-not device/phone-primary?
                  ($ :button
                     {:class    "btn btn-sm btn-primary"
                      :on-click handle-create}
                     "Create a new Map"))
                ;; Hide the search until there's a list to filter — pointless
                ;; chrome on an empty account. Stays put during a background
                ;; refresh (we have maps), so it doesn't flicker.
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

            (empty? filtered)
            ($ :p {:class "text-center py-12 text-gray-500"}
               "No Maps match \"" query "\"")

            :else
            ($ :div {:class "grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4"}
               (for [the-map filtered]
                 ($ map-card {:key       (:id the-map)
                              :the-map   the-map
                              :on-select handle-select}))))

          ($ app-footer)))))
