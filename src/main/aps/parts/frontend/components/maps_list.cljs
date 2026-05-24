(ns aps.parts.frontend.components.maps-list
  "Full-page Maps list route (/app/maps). Fetches the list on mount and
   lets the user open or create a Map. Each Map is rendered as a row
   showing a server-side SVG preview (see ADR-0008), the Map's title,
   and the created / last-updated dates. Selecting a Map navigates the
   client-side router to /app/maps/:id."
  (:require
   [aps.parts.frontend.components.toolbar.auth-status :refer [auth-status]]
   [aps.parts.frontend.router :as router]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect use-state]]
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
  "Format a date-ish value as `YYYY/MM/DD`. Transit deserialises Java
   `Date`/`Instant`/`Timestamp` to a `js/Date` on the cljs side; this
   also accepts an ISO string defensively, and returns nil for anything
   it can't parse."
  [d]
  (when d
    (let [^js dt (if (instance? js/Date d) d (js/Date. d))]
      (when-not (js/isNaN (.getTime dt))
        (let [pad #(.padStart (str %) 2 "0")]
          (str (.getFullYear dt) "/"
               (pad (inc (.getMonth dt))) "/"
               (pad (.getDate dt))))))))

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
     ($ :div {:class "w-28 aspect-square flex-shrink-0 bg-gray-50 flex items-center justify-center border-r border-base-300"}
        ($ :img {:class "max-w-full max-h-full object-contain p-2"
                 ;; `?v=` is a cache-bust fingerprint, not a server param —
                 ;; the handler ignores it. As `:updated_at` advances after
                 ;; an edit, the URL changes and the browser fetches fresh
                 ;; instead of serving its (in-window) cached copy.
                 :src   (str "/api/maps/" (:id the-map) "/preview.svg"
                             (when-let [^js u (:updated_at the-map)]
                               (str "?v=" (.getTime u))))
                 :alt   (str "Preview of " (:title the-map))}))
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

    ($ :div {:class "min-h-screen bg-gray-50 p-4"}
       ($ :div {:class "max-w-3xl mx-auto"}
          ($ :div {:class "flex items-center justify-between my-6"}
             ($ :img {:class "w-40" :src "/images/parts-logo-horizontal.svg"})
             ($ :div {:class "flex items-center gap-2"}
                ($ :button
                   {:class    "btn btn-sm btn-primary"
                    :on-click handle-create}
                   "Create a new Map")
                ($ auth-status)))

          ($ :div {:class "flex items-center justify-between gap-3 mb-4"}
             ($ :h1 {:class "text-lg font-bold"} "Your Maps")
             ;; Hide the search until there's a list to filter — pointless
             ;; chrome on an empty account and while we're still loading.
             (when (and (not loading) (seq maps))
               ($ :input {:type        "search"
                          :placeholder "Filter by title"
                          :class       "input input-bordered input-sm w-56"
                          :value       query
                          :on-change   #(set-query (.. % -target -value))})))

          (cond
            loading
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
                             :on-select handle-select}))))))))
