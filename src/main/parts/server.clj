(ns parts.server
  "Primary namespace for the Parts server setup, handling route definitions,
   middleware configuration, and server lifecycle management. This namespace
   organizes the application's API routes with appropriate content types
   and authentication controls."
  (:require
   [clojure.core.async :as async]
   [com.brunobonacci.mulog :as mulog]
   [muuntaja.core :as muuntaja]
   [org.httpkit.server :as server]
   [parts.api.account :as api.account]
   [parts.api.auth :as api.auth]
   [parts.api.systems :as api.systems]
   [parts.auth :as auth]
   [parts.config :as conf]
   [parts.db :as db]
   [parts.handlers.pages :as pages]
   [parts.handlers.waitlist :as waitlist]
   [parts.middleware :as middleware]
   [reitit.coercion.spec :as rcs]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as rrc]
   [reitit.ring.middleware.muuntaja :as muuntaja-middleware]
   [ring.middleware.params :refer [wrap-params]])
  (:gen-class))

;; ===== Content Type Configuration =====

(def transit-format
  "A configured Muuntaja instance that restricts API format negotiation to
  Transit only (application/transit+json). This instance transforms Clojure
  data structures to/from Transit format when used with appropriate middleware.
  The default format is explicitly set to Transit, so requests without
  an Accept header will receive Transit responses."
  (-> muuntaja/default-options
      (update :formats select-keys ["application/transit+json"])
      (assoc :default-format "application/transit+json")
      muuntaja/create))

;; ===== Middleware Configuration =====

(def base-middleware
  "Common middleware applied to all routes for essential functionality."
  [wrap-params
   middleware/exception
   middleware/logging])

(def html-middleware
  "Middleware specific to HTML routes that ensures responses are properly
  formatted as text/html."
  {:data {:middleware (into base-middleware
                            [middleware/wrap-html-response])}})

(def api-middleware
  "Middleware for API endpoints that need Transit formatting.
  Includes JWT authentication setup but doesn't force authentication
  (individual routes can require auth with the with-auth helper)."
  {:data {:middleware (into base-middleware
                            [muuntaja-middleware/format-middleware
                             rrc/coerce-exceptions-middleware
                             rrc/coerce-request-middleware
                             rrc/coerce-response-middleware
                             middleware/wrap-jwt-authentication])
          :muuntaja transit-format
          :coercion rcs/coercion}})

(defn with-auth
  "Helper function to require authentication for a route."
  [route-data]
  (update route-data :middleware (fnil conj []) middleware/jwt-auth))

;; ===== Route Definitions =====

(def html-routes
  "Public routes that return HTML content. These routes:
  - Don't require authentication
  - Return text/html content type
  - Use the html-middleware stack"
  [["/" {:get {:handler #(pages/home-page %)}}]
   ["/system" {:get {:handler #(pages/system-graph %)}}]
   ["/up" {:get {:handler (fn [_] {:status 200 :body "OK"})}}]
   ["/waitlist-signup" {:post {:handler #(waitlist/signup %)}}]])

(def api-routes
  "API routes that return Transit data. Contains both public and authenticated routes.
  All routes:
  - Return application/transit+json content type
  - Use the api-middleware stack

  Authenticated routes use the with-auth helper to require authentication."
  [["/api"
    ["/ping"
     {:get {:handler (fn [_] {:status 200 :body {:message "Pong!"}})}}]

    ;; Auth routes
    ["/auth"
     ["/login"
      {:post {:handler api.auth/login}}]
     ["/refresh"
      {:post {:handler api.auth/refresh}}]
     ["/logout"
      {:post (with-auth {:handler api.auth/logout})}]]

    ;; Account routes
    ["/account"
     ["/register"
      {:post {:handler api.account/register-account}}]
     [""
      (with-auth {:get {:handler api.account/get-account}
                  :patch {:handler api.account/update-account}
                  :delete {:handler api.account/delete-account}})]]

    ;; Systems routes
    ["/systems"
     ["" {:get {:handler api.systems/list-systems}
          :post {:handler api.systems/create-system}}]
     ["/:id" {:parameters {:path {:id string?}}}
      ["" {:get {:handler api.systems/get-system}
           :put {:handler api.systems/update-system}
           :delete {:handler api.systems/delete-system}}]
      ["/pdf" {:get {:handler api.systems/export-pdf}}]]]]])

;; ===== Router Configuration =====

(defn app
  "Constructs the application's handler function by combining HTML and API routes
  with their appropriate middleware configurations. The function:

  1. Creates a single router with all routes, but preserves the middleware
     configurations for each route type using Reitit's route data mechanism

  2. Wraps the router with the default application middlewares

  This approach ensures proper routing while maintaining the distinct middleware
  needs of HTML and API routes."
  []
  (->
   ;; Create a single router with both HTML and API routes
   ;; Each route maintains its specific middleware config through route data
   (ring/router
    (concat
     ;; Apply html-middleware to all HTML routes
     (for [route html-routes]
       (let [[path data] route]
         [path (merge-with merge data (:data html-middleware))]))

     ;; Apply api-middleware to all API routes
     api-routes)
    ;; Include the api-middleware configuration for API routes
    api-middleware)

   ;; Create handler with default not-found
   (ring/ring-handler (ring/create-default-handler))

   ;; Apply application-wide middleware
   middleware/wrap-default-middlewares))

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
   Initializes the application, starts the server, and returns a shutdown function.

   Arguments:
   - Optional first argument: port number (defaults to 3000)"
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
