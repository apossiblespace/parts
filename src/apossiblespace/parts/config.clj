(ns apossiblespace.parts.config
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]))

(defn get-environment
  []
  (keyword (or (System/getProperty "parts.environment") "default")))

(defn config
  ([]
   (config (get-environment)))
  ([profile]
   (aero/read-config (io/file "config.edn") {:profile profile})))

(defn database-file
  [config]
  (get-in config [:database :file]))

(defn jwt-secret
  [config]
  (get-in config [:auth :secret]))
