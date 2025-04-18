(ns parts.helpers.test-factory
  (:require
   [parts.common.models.part :as part]
   [parts.common.models.relationship :as relationship]
   [parts.common.models.system :as system]
   [parts.common.models.user :as user]))

(def ^:private counter (atom 0))

(defn- generate-unique-id
  []
  (swap! counter inc))

(defn build-test-user
  ([]
   (build-test-user {}))
  ([attrs]
   (let [id (generate-unique-id)]
     (user/make-user (merge
                      {:email (str "test" id "@example.com")
                       :username (str "username" id)
                       :display_name (str "Test User " id)
                       :password (str "password" id)
                       :password_confirmation (str "password" id)
                       :role "client"}
                      attrs)
                     true))))

(defn build-test-users
  [n]
  (repeatedly n build-test-user))
