(ns aps.parts.frontend.device
  "Load-time device classification. Parts is designed for tablets and
   computers; a phone gets a view-only Map (TASK-105), and this is the
   one place that decides what counts as a phone.

   Dependency-free so the kaocha cljs suite can unit-test the predicate
   (that suite carries no re-frame); `app` seeds the flag into app-db
   and components read the constant directly.")

(defn phone?
  "Is a device with this pointer and screen a phone? Coarse primary
   pointer AND a smallest viewport side under 600px: an iPhone (390)
   qualifies in either orientation, an iPad (768) never does, and a
   desktop window dragged narrow keeps its fine pointer."
  [coarse? shortest-side]
  (and coarse? (< shortest-side 600)))

(def phone-primary?
  "This device, classified once at page load. A constant, not a live
   query, for the same reason as `toolbar/touch-primary?`: the
   interaction maps chosen from it must stay identity-stable for
   ReactFlow's memoized renderer — and measuring the smallest side
   (not the current width) means rotating couldn't flip the answer
   anyway."
  (and (exists? js/window)
       (phone? (.-matches (.matchMedia js/window "(pointer: coarse)"))
               (min (.-innerWidth js/window) (.-innerHeight js/window)))))
