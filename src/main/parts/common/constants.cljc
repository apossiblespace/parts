(ns parts.common.constants)

(def part-labels
  {:unknown {:label "Unknown"}
   :manager {:label "Manager"}
   :firefighter {:label "Firefighter"}
   :exile {:label "Exile"}})

(def part-types
  (set (map name (keys part-labels))))

(def relationship-labels
  {:unknown {:label "Unknown"}
   :protective {:label "Protective"}
   :polarization {:label "Polarisation"}
   :alliance {:label "Alliance"}
   :burden {:label "Burden"}
   :blended {:label "Blended"}})

(def relationship-types
  (set (map name (keys relationship-labels))))
