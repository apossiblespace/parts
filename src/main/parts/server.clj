(ns parts.server
  (:require
   [clojure.core.async :as async]
   [com.brunobonacci.mulog :as mulog]
   [org.httpkit.server :as server]
   [parts.api.account :as api.account]
   [parts.api.auth :as api.auth]
   [parts.api.systems :as api.systems]
   [parts.auth :as auth]
   [parts.config :as config]
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

(def html-routes
  [["/" {:get {:handler #(pages/home-page %)}}]
   ["/system" {:get {:handler #(pages/system-graph %)}}]
   ["/up" {:get {:handler (fn [_] {:status 200 :body "OK"})}}]
   ["/waitlist-signup" {:post {:handler #(waitlist/signup %)}}]])

(def api-routes
  [["/swagger.json"
    {:get {:no-doc true
           :swagger {:info {:title "Parts API"
                            :description "API for Parts"}}
           :handler (swagger/create-swagger-handler)}}]
   ["/api"
    ["/ping"
     {:get {:swagger {:tags ["Utility"]}
            :handler (fn [_] {:status 200 :body {:message "Pong!"}})}}]

    ;; Auth routes
    ["/auth" {:swagger {:tags ["Authentication"]}}
     ["/login"
      {:post {:handler api.auth/login}}]
     ["/refresh"
      {:post {:handler api.auth/refresh}}]
     ["/logout"
      {:post {:handler api.auth/logout
              :middleware [middleware/jwt-auth]}}]]

    ;; Account routes
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
                     :handler api.systems/export-pdf}}]]]]])

(defn create-handler [routes middleware-config swagger?]
  (ring/ring-handler
   (ring/router routes middleware-config)
   (if swagger?
     (ring/routes
      (swagger-ui/create-swagger-ui-handler
       {:path "/swagger-ui"
        :url "/swagger.json"
        :config {:validatorUrl nil
                 :operationsSorter "alpha"}})
      (ring/create-default-handler))
     (ring/create-default-handler))))

(def html-middleware
  {:data {:middleware [wrap-params
                       middleware/exception
                       middleware/logging
                       middleware/wrap-html-response]}})

(def api-middleware
  {:data {:middleware [wrap-params
                       middleware/exception
                       middleware/logging
                       [wrap-json-body {:keywords? true}]
                       wrap-json-response
                       middleware/wrap-jwt-authentication]
          :coercion reitit.coercion.spec/coercion}})

(defn app []
  (let [routes (if (config/dev?)
                 (concat html-routes api-routes)
                 html-routes)
        middleware (if (config/dev?)
                     (-> html-middleware
                         (update-in [:data :middleware] concat
                                    (get-in api-middleware [:data :middleware]))
                         (assoc-in [:data :coercion]
                                   (get-in api-middleware [:data :coercion])))
                     html-middleware)]
    (middleware/wrap-default-middlewares
     (create-handler routes middleware (config/dev?)))))

(defn schedule-token-cleanup
  "Schedule periodic cleanup of expired refresh tokens using core.async"
  []
  (let [stop-ch (async/chan)
        interval-ms (* 6 60 60 1000) ; 6 hours in milliseconds
        run-cleanup (fn []
                      (try
                        (let [tokens-removed (auth/cleanup-expired-tokens)]
                          (mulog/log ::token-cleanup-success :tokens_removed (count tokens-removed)))
                        (catch Exception e
                          (mulog/log ::token-cleanup-error
                                     :error (.getMessage e)
                                     :error_type (.getName (class e))))))]
    (run-cleanup)
    (async/go-loop []
      (let [timeout-ch (async/timeout interval-ms)
            [_ ch] (async/alts! [stop-ch timeout-ch])]
        (when (not= ch stop-ch)
          (run-cleanup)
          (recur))))
    stop-ch))

(defn start-server
  "Starts the web server"
  [port]
  (mulog/log ::starting-server :port port)
  (server/run-server (app) {:port port}))

(defn -main
  "Entry point into the application via clojure.main -M"
  [& args]
  (let [port (or (some-> (first args) Integer/parseInt) 3000)]
    (mulog/set-global-context!
     {:app-name "Parts" :version "0.1.0-SNAPSHOT"})
    (mulog/log ::application-startup :arguments args :port port)
    (db/init-db)
    (let [stop-fn (start-server port)
          cleanup-stop-ch (schedule-token-cleanup)]
      (println "Parts: Server started on port" port)
      (fn []
        (stop-fn)
        (async/close! cleanup-stop-ch) ; Signal the cleanup process to stop
        (println "Parts: Server stopped.")))))
