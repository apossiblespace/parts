(ns aps.parts.frontend.components.maps-list-view
  "Pure logic for the Maps list's Active and Archived views. It has no uix
   or React, so the cljs suite can test it directly.")

(defn view
  "The view the URL asks for: `:archived` for `?view=archived`, else
   `:active`."
  [query-params]
  (if (= "archived" (:view query-params)) :archived :active))

(defn- time-of [t]
  (.getTime (js/Date. t)))

(defn maps-in-view
  "The Maps that `view` shows. Active keeps the list's order (most
   recently updated first); Archived is sorted by archive time, newest
   first."
  [maps view]
  (if (= view :archived)
    (->> maps (filter :archived_at) (sort-by #(time-of (:archived_at %)) >))
    (remove :archived_at maps)))
