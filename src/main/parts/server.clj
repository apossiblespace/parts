(ns parts.server
  "Primary namespace for the Parts backend, including the entry point and server
  lifecycle management."
  (:require
   [clojure.core.async :as async]
   [com.brunobonacci.mulog :as mulog]
   [org.httpkit.server :as server]
   [reitit.ring :as ring]
   [parts.config :as conf]
   [parts.routes :as r]
   [parts.db :as db]
   [parts.auth :as auth]
   [parts.middleware :as middleware])
  (:gen-class))

(defn app
  "Constructs the Ring handler function for the entire application.

  Returns a function that processes HTTP requests through the middleware stack:

  1. Core middlewares (static resources, etc.) - outermost layer
  2. Router matching and parameter extraction
  3. Global middleware (logging, exception handling) - applied to all routes
  4. Route-specific middleware (defined in routes config)
  5. Request handlers (defined in routes config) - innermost layer

  This is defined as a function rather than a value to:
  - Allow for lazy initialization at server startup
  - Support route reloading during development
  - Maintain separation between configuration and initialization

  The handler processes requests by matching routes, applying appropriate
  middleware, and falling back to the default handler for unmatched routes."
  []
  (fn [req]
    (let [handler
          (-> (ring/router (r/routes)
                           {:data {:middleware [middleware/logging
                                                middleware/exception]}})
              (ring/ring-handler (ring/create-default-handler))
              middleware/wrap-core-middlewares)]
      (handler req))))

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
  "Starts the web server with the configured application handler.
   Returns a function that can be called to stop the server."
  [port]
  (mulog/log ::starting-server :port port)
  (server/run-server (app) {:port port}))

(defn -main
  "Entry point into the application via clojure.main -M.
   Initializes the application, starts the server, and returns a shutdown function."
  [& args]
  (let [port (or (some-> (first args) Integer/parseInt) 3000)]
    ;; Set up global logging context
    (mulog/set-global-context!
     {:app-name "Parts" :version "0.1.0-SNAPSHOT", :env (conf/get-environment)})
    (mulog/log ::application-startup :arguments args :port port)

    ;; Initialize database
    (db/init-db)

    ;; Start server and background processes
    (let [stop-fn (start-server port)
          cleanup-stop-ch (schedule-token-cleanup)]
      (println "Parts: Server started on port" port)

      ;; Return shutdown function
      (fn []
        (stop-fn)
        (async/close! cleanup-stop-ch) ; Signal the cleanup process to stop
        (println "Parts: Server stopped.")))))
