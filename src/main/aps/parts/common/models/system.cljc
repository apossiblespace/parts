(ns aps.parts.common.models.system
  (:require
   [aps.parts.common.utils :refer [validate-spec]]
   [clojure.spec.alpha :as s]))

(s/def ::id (s/or :string string? :uuid uuid?))
(s/def ::title string?)
(s/def ::owner_id (s/or :string string? :uuid uuid?))
(s/def ::viewport_settings (s/nilable string?))

(s/def ::system (s/keys :req-un [::title
                                 ::owner_id]
                        :opt-un [::id
                                 ::viewport_settings]))

(def spec
  "System model spec for reuse outside of the namespace"
  ::system)

(defn make-system
  "Create a new System with the given attributes. 
   In ClojureScript (frontend), generates a string UUID for :id. 
   In Clojure (backend), :id is set by the database layer."
  [attrs]
  (println "[make-system]" attrs)
  (let [base   {:title "Untitled System"}
        system #?(:cljs (merge {:id (str (random-uuid))} base attrs)
                  :clj (merge base attrs))]
    (validate-spec ::system system)
    system))
