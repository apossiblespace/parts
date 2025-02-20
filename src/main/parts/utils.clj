(ns parts.utils
  "Various useful utilities."
  (:require
   [clojure.spec.alpha :as s]))

(defn validate-spec [spec data]
  (when-not (s/valid? spec data)
    (throw (ex-info "Validation failed"
                    {:type :validation
                     :explain (s/explain-str spec data)}))))
