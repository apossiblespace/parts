(ns apossiblespace.parts.config
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]))

(defn config
  ([]
   ;; TODO: Pass a default based on an env var?
   (config :default))
  ([profile]
   (aero/read-config (io/file "config.edn") {:profile profile})))

(defn database-file [config]
  (get-in config [:database :file]))
