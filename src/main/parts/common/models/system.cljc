(ns parts.common.models.system
  (:require
   [clojure.spec.alpha :as s]
   [parts.common.utils :refer [validate-spec]]))

(s/def ::id string?)
(s/def ::title string?)
(s/def ::owner_id string?)
(s/def ::viewport_settings (s/nilable string?))

(s/def ::system (s/keys :req-un [::id
                                 ::title
                                 ::owner_id]
                        :opt-un [::viewport_settings]))

(def spec
  "System model spec for reuse outside of the namespace"
  ::system)

(defn make-system
  "Create a new System with the given attributes"
  [attrs]
  (println "[make-system]" attrs)
  (let [system (merge
                {:id (str (random-uuid))
                 :title "Untitled System"}
                attrs)]
    (validate-spec ::system system)
    system))
