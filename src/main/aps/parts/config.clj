(ns aps.parts.config
  (:require
   [clojure.pprint :as pprint]
   [clojure.string :as cstr]
   [lambdaisland.config :as l-config]))

;; Expose config directly for consumer access
;; Use (l-config/get config :key) to access configuration values
(def config
  (l-config/create {:prefix "parts"}))

(defn get-environment
  "Get the current environment. lambdaisland/config determines this from:
  1. parts.env Java system property
  2. PARTS_ENV environment variable
  3. CI=true defaults to :test
  4. Falls back to :dev"
  []
  (:env config))

(defn prod?
  "Are we in the PRODUCTION environment?"
  []
  (= (get-environment) :prod))

(defn test?
  "Are we in the TEST environment?"
  []
  (= (get-environment) :test))

(defn dev?
  "Are we in the DEVELOPMENT environment?"
  []
  (= (get-environment) :dev))

(defn host-uri
  "Get the full qualified application host URI, eg: http://localhost:3000"
  []
  (str (l-config/get config :http/protocol)
       "://"
       (l-config/get config :http/host)
       ":"
       (l-config/get config :http/port)))

(defn print-config-table
  "Print all accessed configuration keys, values, and sources as a table."
  []
  (let [cached @(:values config)
        rows   (for [[k v] (sort-by key cached)]
                 {:key    k
                  :value  (pr-str (:val v))
                  :source (-> (:source v)
                              str
                              (cstr/replace #"^file:" "")
                              (cstr/replace #".*/resources/" "resources/"))})]
    (if (seq rows)
      (pprint/print-table [:key :value :source] rows)
      (println "No configuration values have been accessed yet."))))

