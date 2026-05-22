(ns aps.parts.launch
  "Runtime launch toggle. Controls which landing page is served and whether
   account-registration is open. Seeded from `:launch/launched?` config at
   namespace load; flip at runtime from the prod nREPL with `enable!` /
   `disable!`."
  (:require
   [aps.parts.config :as conf]
   [com.brunobonacci.mulog :as mulog]
   [lambdaisland.config :as l-config]))

(defonce launched-state
  (atom (conf/parse-bool (l-config/get conf/config :launch/launched?))))

(defn launched? [] @launched-state)
(defn status    [] {:launched? @launched-state})

(defn enable!
  []
  (reset! launched-state true)
  (mulog/log ::launched-flipped :launched? true)
  (status))

(defn disable!
  []
  (reset! launched-state false)
  (mulog/log ::launched-flipped :launched? false)
  (status))
