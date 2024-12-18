;; ---------------------------------------------------------
;; tools.ifs.parts
;;
;; Parts is a toolkit for therapists working with the Internal Family Systems
;; model (https://en.wikipedia.org/wiki/Internal_Family_Systems_Model). It
;; provides a tool for easy, collaborative parts mapping, which can be used to
;; facilitate conversations with clients during sessions.

;; ---------------------------------------------------------

(ns tools.ifs.parts
  (:require
   [com.brunobonacci.mulog :as mulog]
   [org.httpkit.server :as server]
   [reitit.coercion.spec]
   [reitit.ring :as ring]
   [reitit.swagger :as swagger]
   [reitit.swagger-ui :as swagger-ui]
   [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
   [ring.middleware.params :refer [wrap-params]]
   [tools.ifs.parts.api.account :as account]
   [tools.ifs.parts.api.auth :as auth]
   [tools.ifs.parts.api.middleware :as middleware]
   [tools.ifs.parts.db :as db]
   [tools.ifs.parts.pages :as pages]
   [tools.ifs.parts.waitlist :as waitlist])
  (:gen-class))

;; ---------------------------------------------------------
;; Application

(def prelaunch-app
  (middleware/wrap-default-middlewares
    (ring/ring-handler
      (ring/router
        [["/" {:get {:handler #(pages/home-page %)}}]
         ["/up"
          {:get {:handler (fn [_] {:status 200 :body "OK"})}}]
         ["/waitlist-signup" {:post {:handler #(waitlist/signup %)}}]]
        {:data {:middleware [wrap-params
                             middleware/exception
                             middleware/logging
                             middleware/wrap-html-response]}}))))

;; TODO: We need to later figure out a way to combine HTML routes and API
;; routes.
;;
;; Currently, the main issue is that I cannot figure out a way to combine API
;; namespaces with different sets of middleware for each.
;;
;; For example, I want the /api namespace to have JSON-related middlewares, but
;; not the root namespace, which serves text/html instead.
;;
;; It is also entirely possible that API routes will be removed, and only HTML
;; routes will remain.
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
         ["/" {:get {:handler #(pages/home-page %)}}]]
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
  (server/run-server #'prelaunch-app {:port port}))

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
