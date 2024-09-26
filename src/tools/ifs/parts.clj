;; ---------------------------------------------------------
;; tools.ifs.parts
;;
;; TODO: Provide a meaningful description of the project
;; ---------------------------------------------------------

(ns tools.ifs.parts
  (:gen-class)
  (:require
   [com.brunobonacci.mulog :as mulog]
   [org.httpkit.server :as server]
   [reitit.ring :as ring]
   [reitit.coercion.spec]
   [reitit.swagger :as swagger]
   [reitit.swagger-ui :as swagger-ui]
   [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
   [ring.middleware.params :refer [wrap-params]]
   [tools.ifs.parts.api.middleware :as middleware]
   [tools.ifs.parts.db :as db]
   [tools.ifs.parts.pages :as pages]
   [tools.ifs.parts.api.auth :as auth]
   [tools.ifs.parts.api.account :as account]))

;; ---------------------------------------------------------
;; Application

(def app
  (middleware/wrap-default-middlewares
   (ring/ring-handler
    (ring/router
     [["/swagger.json"
       {:get {:no-doc true
              :swagger {:info {:title "Parts API"
                               :description "API for Parts"}}
              :handler (swagger/create-swagger-handler)}}]
      ["/api"
       ["/ping"
        {:get {:swagger {:tags ["Utility"]}
               :handler (fn [_] {:status 200 :body {:message "Pong!"}})}}]
       ["/auth" {:swagger {:tags ["Authentication"]}}
        ["/login"
         {:post {:handler auth/login}}]
        ["/logout"
         {:post {:handler auth/logout
                 :middleware [middleware/jwt-auth]}}]]
       ["/account" {:swagger {:tags ["Account"]}}
        [""
         {:get {:handler account/get-account}
          :patch {:handler account/update-account}
          :delete {:handler account/delete-account}
          :middleware [middleware/jwt-auth]}]
        ["/register"
         {:post {:handler account/register-account}}]]]
      ["/" {:get pages/home-page}]]
     {:data {:middleware [wrap-params
                          middleware/exception
                          middleware/logging
                          [wrap-json-body {:keywords? true}]
                          wrap-json-response
                          middleware/wrap-jwt-authentication]}})
    (ring/routes
     (swagger-ui/create-swagger-ui-handler
      {:path "/swagger-ui"
       :config {:validatorUrl nil
                :operationsSorter "alpha"}})
     (ring/create-default-handler)))))

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
      (println "Parts: Server started on port" port)
      (fn []
        (stop-fn)
        (println "Parts: Server stopped.")))))

;; ---------------------------------------------------------


;; ---------------------------------------------------------
;; Rick Comment
#_{:clj-kondo/ignore [:redefined-var]}
(comment
  (def stop-server (-main))
  (stop-server)
  #_()) ; End of rich comment block
;; ---------------------------------------------------------
