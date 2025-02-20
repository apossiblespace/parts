(ns parts.server
  (:require
   [com.brunobonacci.mulog :as mulog]
   [org.httpkit.server :as server]
   [parts.api.account :as api.account]
   [parts.api.auth :as api.auth]
   [parts.api.systems :as api.systems]
   [parts.db :as db]
   [parts.handlers.pages :as pages]
   [parts.handlers.waitlist :as waitlist]
   [parts.middleware :as middleware]
   [reitit.coercion.spec]
   [reitit.ring :as ring]
   [reitit.swagger :as swagger]
   [reitit.swagger-ui :as swagger-ui]
   [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
   [ring.middleware.params :refer [wrap-params]])
  (:gen-class))

;; (def app
;;   (middleware/wrap-default-middlewares
;;    (ring/ring-handler
;;     (ring/router
;;      [["/" {:get
;;             {:handler (fn [req]
;;                         (tap> req) ;; This will display req in the inspector
;;                         {:status 200})
;;                   }}]]))))

(def app
  (middleware/wrap-default-middlewares
    (ring/ring-handler
      (ring/router
        [["/" {:get {:handler #(pages/home-page %)}}]
         ["/system" {:get {:handler #(pages/system-graph %)}}]
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
(def api
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

          ;; Authentication routes
          ["/auth" {:swagger {:tags ["Authentication"]}}
           ["/login"
            {:post {:handler api.auth/login}}]
           ["/logout"
            {:post {:handler api.auth/logout
                    :middleware [middleware/jwt-auth]}}]]

          ;; Account management routes
          ["/account" {:swagger {:tags ["Account"]}}
           [""
            {:get {:handler api.account/get-account}
             :patch {:handler api.account/update-account}
             :delete {:handler api.account/delete-account}
             :middleware [middleware/jwt-auth]}]
           ["/register"
            {:post {:handler api.account/register-account}}]]

          ;; Systems routes
          ["/systems" {:swagger {:tags ["Systems"]}
                      :middleware [middleware/jwt-auth]}
           ["" {:get {:summary "List all systems for current user"
                     :handler api.systems/list-systems}
                :post {:summary "Create new system"
                      :handler api.systems/create-system}}]
           ["/:id" {:parameters {:path {:id string?}}}
            ["" {:get {:summary "Get system by ID"
                      :handler api.systems/get-system}
                 :put {:summary "Update entire system"
                      :handler api.systems/update-system}
                 :delete {:summary "Delete system"
                         :handler api.systems/delete-system}}]
            ["/pdf" {:get {:summary "Generate PDF export of system"
                          :handler api.systems/export-pdf}}]]]]
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
