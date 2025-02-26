(ns parts.config
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]))

(def valid-environments #{"development" "test" "production"})

(defn get-environment
  "Get application environment from PARTS_ENV environment variable.
  Defaults to 'development' if not set or invalid.'"
  []
  (let [env (or (System/getProperty "PARTS_ENV")   ; Try Java property first
                (System/getenv "PARTS_ENV")        ; Then environment variable
                "development")]
    (if (contains? valid-environments env)
      (keyword env)
      :development)))

(defn config
  "Get configuration for current environment.
  Uses PARTS_ENV environment variable to determine environment."
  ([]
   (config (get-environment)))
  ([profile]
   (aero/read-config (io/resource "config.edn") {:profile profile})))

(defn database-file
  [config]
  (get-in config [:database :file]))

(defn jwt-secret
  [config]
  (get-in config [:auth :secret]))

(defn prod?
  "Are we in the PRODUCTION environment?"
  []
  (= (get-environment) :production))

(defn test?
  "Are we in the TEST environment?"
  []
  (= (get-environment) :test))

(defn dev?
  "Are we in the DEVELOPMENT environment?"
  []
  (= (get-environment) :development))

(defn host-uri
  "Get the full qualified application host uri based on the configuration and
  environment, eg: http://localhost:3000"
  [config]
  (str (get-in config [:app :protocol])
       "://"
       (get-in config [:app :host])))
