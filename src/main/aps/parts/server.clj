(ns aps.parts.server
  "Primary namespace for the Parts backend, including the entry point and server
  lifecycle management."
  (:require
   [aps.parts.alerts :as alerts]
   [aps.parts.common.observe :as observe]
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [aps.parts.errors :as errors]
   [aps.parts.jobs.deletion-purge :as deletion-purge]
   [aps.parts.jobs.session-cleanup :as session-cleanup]
   [aps.parts.middleware :as middleware]
   [aps.parts.routes :as r]
   [aps.parts.version :as version]
   [clojure.core.async :as async]
   [com.brunobonacci.mulog :as mulog]
   [lambdaisland.config :as l-config]
   [nrepl.server :as nrepl]
   [org.httpkit.server :as server]
   [reitit.ring :as ring]
   [ring.middleware.head :as head])
  (:import
   [java.nio.file Files Path]
   [java.nio.file.attribute PosixFilePermissions])
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
                                                errors/exception]}})
              (ring/ring-handler (ring/create-default-handler))
              middleware/wrap-core-middlewares
              ;; Treat HEAD like GET (body stripped) on every route — reitit
              ;; doesn't synthesize HEAD, so without this a HEAD probe gets 405.
              ;; Health monitors (and the free UptimeRobot tier) only do HEAD.
              head/wrap-head)]
      (handler req))))

(defn start-log-publisher
  "Starts a mulog console-json publisher in prod, sending one JSON event per
   line to stdout (captured by systemd's journald). Returns a 0-arity stop
   function that flushes the buffer and stops the publisher, or nil in
   non-prod environments where another mechanism (e.g. the dev tap publisher
   in src/dev/mulog_events.clj) handles publishing."
  []
  (when (conf/prod?)
    (mulog/start-publisher! {:type      :console-json
                             :pretty?   false
                             :transform observe/mulog-transform})))

(defn start-alert-publisher
  "Starts the operator error-alert publisher when SMTP is configured. Gated on
   creds-present, not environment, so a staging-shaped box can alert too (and
   dev, with nothing set, simply no-ops). Returns a 0-arity stop function, or
   nil when SMTP is unconfigured — in which case alerting is off and a
   `::alerting-disabled` event records why."
  []
  (if-let [smtp (conf/smtp-config)]
    (mulog/start-publisher!
     {:type         :custom
      :fqn-function "aps.parts.alerts/publisher"
      :smtp         smtp
      :domain       (conf/app-domain)
      :cooldown-ms  alerts/default-cooldown-ms})
    (do (mulog/log ::alerting-disabled
                   :reason "SMTP not configured (PARTS__SMTP__*)")
        nil)))

(defn- owner-only!
  "Restrict `path` to rw for its owner (0600). nREPL creates the socket with
   the process umask, which typically leaves it group/world-connectable."
  [path]
  (Files/setPosixFilePermissions
   (Path/of path (into-array String []))
   (PosixFilePermissions/fromString "rw-------")))

(defn- preload-ops!
  "Preload the operator console so a connected REPL has it at hand."
  []
  (try
    (require 'aps.parts.ops)
    (catch Exception e
      (mulog/log ::ops-preload-failed :error (.getMessage e)))))

(defn start-nrepl
  "Starts the production nREPL on a unix domain socket (:repl/socket),
   permissioned 0600 — nREPL has no authentication, so \"may connect\" must
   mean filesystem access as the app user, not merely \"is local\"
   (a TCP loopback port is connectable by ANY local process). A loopback
   TCP REPL remains available as an explicit operator escape hatch via
   PARTS__REPL__PORT, which prod.edn deliberately does not set.
   Returns the server instance or nil if disabled."
  []
  (when (conf/prod?)
    (try
      (if-let [socket-path (l-config/get conf/config :repl/socket)]
        (let [server (nrepl/start-server :socket socket-path)]
          (owner-only! socket-path)
          (mulog/log ::nrepl-started :socket socket-path)
          (println (format "nREPL server started on unix socket %s" socket-path))
          (preload-ops!)
          server)
        (when-let [repl-port (l-config/get conf/config :repl/port)]
          (let [port         (Integer/parseInt repl-port)
                bind-address (or (l-config/get conf/config :repl/host) "127.0.0.1")
                server       (nrepl/start-server :bind bind-address :port port)]
            (mulog/log ::nrepl-started :port port :bind bind-address)
            (println (format "nREPL server started on %s:%d" bind-address port))
            (preload-ops!)
            server)))
      (catch Exception e
        (mulog/log ::nrepl-start-error
                   :error (.getMessage e)
                   :error_type (.getName (class e)))
        (println "Failed to start nREPL server:" (.getMessage e))
        nil))))

(defn start-server
  "Starts the web server with the configured application handler.
   Returns a function that can be called to stop the server."
  [port]
  (mulog/log ::starting-server :port port)
  ;; Explicit request-size ceiling: a full change-batch (max-change-batch
  ;; changes with max-text-length notes) is ~5 MB of transit; 8 MB leaves
  ;; headroom without letting one request buffer arbitrary bytes.
  (server/run-server (app) {:port port :max-body (* 8 1024 1024)}))

(defn -main
  "Entry point into the application via clojure.main -M.
   Initializes the application, starts the server, and returns a shutdown
   function."
  [& args]
  (let [port         (conf/http-port)
        stop-log-pub (start-log-publisher)]
    ;; Set up global logging context. `version` is the build-stamped git hash
    ;; (resources/parts/version.txt), so every event — error alerts included —
    ;; is attributable to the deploy that produced it.
    (mulog/set-global-context!
     {:app-name "Parts" :version (version/current), :env (conf/get-environment)})
    (mulog/log ::application-startup :arguments args :port port)

    ;; Initialize database
    (db/init-db)

    ;; Start nREPL server if configured
    (let [stop-alert-pub   (start-alert-publisher)
          nrepl-server     (start-nrepl)
          ;; Start server and background processes
          stop-fn          (start-server port)
          deletion-stop-ch (deletion-purge/schedule!)
          sessions-stop-ch (session-cleanup/schedule!)]
      (println "Parts: Server started on port" port)

      ;; Print configuration on startup
      (println "\nConfiguration:")
      (conf/print-config-table)

      ;; Return shutdown function
      (fn []
        (stop-fn)
        (async/close! deletion-stop-ch)
        (async/close! sessions-stop-ch)
        (when nrepl-server
          (nrepl/stop-server nrepl-server)
          (println "nREPL server stopped"))
        (mulog/log ::application-shutdown)
        (when stop-alert-pub (stop-alert-pub))
        (when stop-log-pub (stop-log-pub))
        (println "Parts: Server stopped.")))))
