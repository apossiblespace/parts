(ns aps.parts.version
  "Build-stamped app version. The deployed short git commit hash is written
   to `resources/parts/version.txt` by `make build-uberjar`; in dev or any
   environment without that file, callers see the literal \"dev\"."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private resource-path "parts/version.txt")

(defn current
  "Return the current app version as a string. Reads `parts/version.txt`
   from the classpath; falls back to \"dev\" when the resource is absent."
  []
  (or (some-> (io/resource resource-path) slurp str/trim) "dev"))
