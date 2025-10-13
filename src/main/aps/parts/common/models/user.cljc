(ns aps.parts.common.models.user
  (:require
   [clojure.spec.alpha :as s]
   [aps.parts.common.utils :refer [validate-spec]]))

(s/def ::id string?)
(s/def ::email (s/and string? #(re-matches #"^.+@.+\..+$" %)))
(s/def ::username (s/and string? not-empty))
(s/def ::display_name (s/and string? not-empty))
(s/def ::password (s/and string? not-empty))
(s/def ::password_confirmation (s/and string? not-empty))
(s/def ::role #{"client" "therapist"})

(s/def ::user
  (s/keys :req-un [::id
                   ::email
                   ::username
                   ::display_name
                   ::role]
          :opt-un [::password
                   ::password_confirmation]))

(defn password-match? [user]
  (= (:password user) (:password_confirmation user)))

(s/def ::user-with-password
  (s/and ::user password-match?))

(def spec
  "User model spec for reuse outside of the namespace"
  ::user)

(def spec-with-password
  "User model with confirmed password spec for reuse outside of the namespace"
  ::user-with-password)

(defn make-user
  "Create a new User with the given attributes"
  ([attrs]
   (make-user attrs false))
  ([attrs validate-password?]
   (println "[make-user]" attrs)
   (let [user (merge
               {:id (str (random-uuid))}
               attrs)]
     (validate-spec (if validate-password?
                      ::user-with-password
                      ::user)
                    user)
     user)))
