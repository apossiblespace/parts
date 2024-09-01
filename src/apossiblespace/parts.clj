;; ---------------------------------------------------------
;; apossiblespace.parts
;;
;; TODO: Provide a meaningful description of the project
;; ---------------------------------------------------------

(ns apossiblespace.parts
  (:gen-class)
  (:require 
   [com.brunobonacci.mulog :as mulog]
   [org.httpkit.server :as server]
   [reitit.ring :as ring]
   [reitit.coercion.spec]
   [reitit.swagger :as swagger]
   [reitit.swagger-ui :as swagger-ui]
   [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
   [apossiblespace.parts.db :as db]
   [apossiblespace.parts.auth :as auth]))

;; ---------------------------------------------------------
;; Application

(def app
  (ring/ring-handler
   (ring/router
    [["/swagger.json"
      {:get {:no-doc true
              :swagger {:info {:title "Parts API"
                               :description "API for Parts"}}
              :handler (swagger/create-swagger-handler)}}]
     ["/api"
      ["/health-check"
       {:get {:handler (fn [_] {:status 200 :body {:message "Service is up"}})}}]
      ["/auth"
       ["/register"
        {:post {:handler auth/register}}]
       ["/login"
        {:post {:handler auth/login}}]
       ["/logout"
        {:post {:handler auth/logout
                :middleware [auth/jwt-auth]}}]]
      ]]
    {:data {:middleware [[wrap-json-body {:keywords? true}]
                         wrap-json-response]}})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler
     {:path "/"
      :config {:validatorUrl nil
               :operationsSorter "alpha"}})
    (ring/create-default-handler))))

(defn start-server
  "Starts the web server"
  [port]
  (mulog/log ::starting-server :port port)
  (server/run-server #'app {:port port}))


(defn -main
  "Entry point into the application via clojure.main -M"
  [& args]
  (let [port (or (some-> (first args) Integer/parseInt) 3000)]
    (mulog/set-global-context!
     {:app-name "Parts" :version "0.1.0-SNAPSHOT"})
    (mulog/log ::application-startup :arguments args :port port)
    (db/init-db)
    (let [stop-fn (start-server port)]
      (println "Server started on port " port)
      (fn []
        (stop-fn)
        (println "Server stopped.")))))

;; ---------------------------------------------------------


;; ---------------------------------------------------------
;; Rick Comment
#_{:clj-kondo/ignore [:redefined-var]}
(comment
  (def stop-server (-main))
  (stop-server)
  #_()) ; End of rich comment block
;; ---------------------------------------------------------