(ns aps.parts.server
  "Primary namespace for the Parts backend, including the entry point and server
  lifecycle management."
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [aps.parts.middleware :as middleware]
   [aps.parts.routes :as r]
   [clojure.core.async :as async]
   [com.brunobonacci.mulog :as mulog]
   [lambdaisland.config :as l-config]
   [nrepl.server :as nrepl]
   [org.httpkit.server :as server]
   [reitit.ring :as ring])
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
  (let [stop-ch     (async/chan)
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
            [_ ch]     (async/alts! [stop-ch timeout-ch])]
        (when (not= ch stop-ch)
          (run-cleanup)
          (recur))))
    stop-ch))

(defn start-nrepl
  "Starts an nREPL server if enabled via environment configuration.
   Returns the server instance or nil if disabled."
  []
  (when conf/prod?
    (when-let [repl-port (l-config/get conf/config :repl/port)]
      (try
        (let [port         (Integer/parseInt repl-port)
              bind-address (or (l-config/get conf/config :repl/host) "127.0.0.1")
              server       (nrepl/start-server :bind bind-address :port port)]
          (mulog/log ::nrepl-started :port port :bind bind-address)
          (println (format "nREPL server started on %s:%d" bind-address port))
          server)
        (catch Exception e
          (mulog/log ::nrepl-start-error
                     :error (.getMessage e)
                     :error_type (.getName (class e)))
          (println "Failed to start nREPL server:" (.getMessage e))
          nil)))))

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

    ;; Start nREPL server if configured
    (let [nrepl-server    (start-nrepl)
          ;; Start server and background processes
          stop-fn         (start-server port)
          cleanup-stop-ch (schedule-token-cleanup)]
      (println "Parts: Server started on port" port)

      ;; Print configuration on startup
      (println "\nConfiguration:")
      (conf/print-config-table)

      ;; Return shutdown function
      (fn []
        (stop-fn)
        (async/close! cleanup-stop-ch) ; Signal the cleanup process to stop
        (when nrepl-server
          (nrepl/stop-server nrepl-server)
          (println "nREPL server stopped"))
        (println "Parts: Server stopped.")))))
