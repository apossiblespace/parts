(ns aps.parts.common.utils
  "Various useful utilities."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]))

(defn validate-spec [spec data]
  (when-not (s/valid? spec data)
    (throw (ex-info "Validation failed"
                    {:type    :validation
                     :explain (s/explain-str spec data)}))))

(defn normalize-email
  "Normalize an email address for storage and lookup: trim surrounding
   whitespace and lowercase. Returns nil for nil input. Apply at every site
   where email enters the system (writes AND reads) — asymmetry causes silent
   lookup failures."
  [email]
  (when email
    (-> email str/trim str/lower-case)))
