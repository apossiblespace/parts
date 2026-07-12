(ns aps.parts.frontend.components.maps-list
  "Full-page Maps list route (/app/maps). Fetches the list on mount and
   lets the user open or create a Map. Each Map is rendered as a row
   showing a server-side SVG preview (see ADR-0008), the Map's title,
   and the created / last-updated dates. Selecting a Map navigates the
   client-side router to /app/maps/:id."
  (:require
   [aps.parts.frontend.components.app-footer :refer [app-footer]]
   [aps.parts.frontend.components.app-header :refer [app-header]]
   [aps.parts.frontend.dates :as dates]
   [aps.parts.frontend.router :as router]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect use-layout-effect use-ref use-state]]
   [uix.re-frame :as uix.rf]))

(defn- title-matches?
  "Case-insensitive substring match of `query` against `the-map`'s title.
   A blank query matches everything; a Map with no title matches nothing
   unless the query is also blank."
  [query the-map]
  (let [q (.. (or query "") trim toLowerCase)]
    (or (zero? (.-length q))
        (let [t (.. (or (:title the-map) "") toLowerCase)]
          (.includes t q)))))

(defn- fmt-date
  "Format a date-ish value as `YYYY/MM/DD`, or nil when unparseable."
  [d]
  (when-let [^js dt (dates/->js-date d)]
    (let [pad #(.padStart (str %) 2 "0")]
      (str (.getFullYear dt) "/"
           (pad (inc (.getMonth dt))) "/"
           (pad (.getDate dt))))))

(defui ^:private map-preview
  "The server-rendered SVG preview for one Map (ADR-0008). Owns a `loaded?`
   flag so a *changed* preview shows a skeleton while its new image loads,
   without disturbing previews that didn't change."
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
    ($ :div {:class (str "relative w-28 aspect-square flex-shrink-0 bg-gray-50 "
                         "flex items-center justify-center border-r border-base-300")}
       ($ :img {:ref      img-ref
                :class    "max-w-full max-h-full object-contain p-2"
                :src      src
                :alt      (str "Preview of " (:title the-map))
                :on-load  #(set-loaded! true)
                ;; A broken image must resolve too, or it shimmers forever.
                :on-error #(set-loaded! true)})
       (when-not loaded?
         ($ :div {:class "absolute inset-0 skeleton"})))))

(defui ^:private map-row
  "One row in the Maps list: a clickable button with the server-rendered
   SVG preview on the left, and the Map's title + Created/Updated dates
   on the right."
  [{:keys [the-map on-select]}]
  ($ :button
     {:class    (str "w-full flex items-stretch cursor-pointer "
                     "bg-white border border-base-300 rounded-lg "
                     "shadow-sm hover:shadow-md transition-shadow "
                     "text-left p-0 overflow-hidden")
      :on-click #(on-select the-map)}
     ($ map-preview {:the-map the-map})
     ($ :div {:class "flex-1 px-4 py-3 flex flex-col justify-center min-w-0"}
        ($ :h2 {:class "text-base font-medium truncate"}
           (:title the-map))
        ($ :p {:class "text-xs text-gray-500 mt-2"}
           "Created: " (fmt-date (:created_at the-map))
           (when-let [updated (fmt-date (:updated_at the-map))]
             (str "    Updated: " updated))))))

(defui maps-list []
  (let [maps              (uix.rf/use-subscribe [:maps/list])
        loading           (uix.rf/use-subscribe [:maps/loading])
        [query set-query] (use-state "")
        filtered          (filter (partial title-matches? query) maps)

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

          ($ :div {:class "flex items-center justify-between gap-3 mb-4"}
             ($ :h1 {:class "text-lg font-bold"} "Your Maps")
             ($ :div {:class "flex items-center gap-2"}
                ($ :button
                   {:class    "btn btn-sm btn-primary"
                    :on-click handle-create}
                   "Create a new Map")
                ;; Hide the search until there's a list to filter — pointless
                ;; chrome on an empty account. Stays put during a background
                ;; refresh (we have maps), so it doesn't flicker.
                (when (seq maps)
                  ($ :input {:type        "search"
                             :placeholder "Filter by title"
                             :class       "input input-bordered input-sm w-56"
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
            ($ :div {:class "flex flex-col gap-3"}
               (for [the-map filtered]
                 ($ map-row {:key       (:id the-map)
                             :the-map   the-map
                             :on-select handle-select}))))

          ($ app-footer)))))
