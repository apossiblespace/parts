(ns aps.parts.frontend.components.maps-list
  "Full-page Maps list route (/app/maps). Fetches the list on mount and
   lets the user open or create a Map. Each Map is rendered as a row
   showing a server-side SVG preview (see ADR-0008), the Map's title,
   and the created / last-updated dates. Selecting a Map navigates the
   client-side router to /app/maps/:id."
  (:require
   [aps.parts.frontend.router :as router]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect]]
   [uix.re-frame :as uix.rf]))

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
  (let [maps          (uix.rf/use-subscribe [:maps/list])
        loading       (uix.rf/use-subscribe [:maps/loading])

        handle-create (fn []
                        (rf/dispatch [:map/create]))

        handle-select (fn [the-map]
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
             ($ :a {:href "/" :class "flex items-center"}
                ($ :img {:class "w-40" :src "/images/parts-logo-horizontal.svg"}))
             ($ :button
                {:class    "btn btn-sm btn-primary"
                 :on-click handle-create}
                "Create a new Map"))

          ($ :h1 {:class "text-lg font-bold mb-4"} "Your Maps")

          (cond
            loading
            ($ :div {:class "flex justify-center py-12"}
               ($ :div {:class "loading loading-spinner"}))

            (empty? maps)
            ($ :p {:class "text-center py-12 text-gray-500"}
               "No Maps yet")

            :else
            ($ :div {:class "flex flex-col gap-3"}
               (for [the-map maps]
                 ($ map-row {:key       (:id the-map)
                             :the-map   the-map
                             :on-select handle-select}))))))))
