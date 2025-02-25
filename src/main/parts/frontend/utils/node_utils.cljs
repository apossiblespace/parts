(ns parts.frontend.utils.node-utils
  (:require [parts.common.constants :refer [part-labels]]))

(defn build-updated-part [node-map form-data]
  (let [old-type (keyword (:type node-map))
        new-type (keyword (:type form-data))
        old-label (get-in node-map [:data :label])
        old-default-label (get-in part-labels [old-type :label])
        new-label (if (and (= old-label old-default-label)
                           (= (:label form-data) old-label))
                   ;; Only use new default label if:
                   ;; 1. Current label is the default for old type
                   ;; 2. User hasn't explicitly changed the label
                    (get-in part-labels [new-type :label])
                    (:label form-data))]
    (-> node-map
        (assoc :type (name new-type))
        (assoc :data {"label" new-label}))))
