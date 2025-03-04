(ns parts.frontend.state)

;; TODO: State management!

(defn set-entities! [data]
  (println "set-entities!" data))

(defn add-entity! [data]
  (println "add-entity!" data))

(defn update-entity! [data]
  (println "update-entity!" data))

(defn remove-entity! [data]
  (println "delete-entity!" data))

(defn set-error! [data]
  (println "set-error!" data))

(defn get-auth-token []
  (str "placeholder-token-change-me"))

(defn init! []
  (println "State manager initialised"))
