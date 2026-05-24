(ns aps.parts.common.constants)

(def part-labels
  {:unknown     {:label "Unknown"}
   :manager     {:label "Manager"}
   :firefighter {:label "Firefighter"}
   :exile       {:label "Exile"}})

(def part-types
  (set (map name (keys part-labels))))

(def relationship-labels
  {:unknown      {:label "Unknown"}
   :protective   {:label "Protective"}
   :polarization {:label "Polarisation"}
   :alliance     {:label "Alliance"}
   :burden       {:label "Burden"}
   :blended      {:label "Blended"}})

(def relationship-types
  (set (map name (keys relationship-labels))))

(def brand-suffix
  "The suffix appearing after the page title in the <title> element"
  "Parts: IFS parts mapping for therapists and their clients")
