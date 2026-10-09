(ns aps.parts.console
  "Ring handler for the Operator console, the operator's web UI for fleet
   stats.

   `aps.parts.server` serves this handler on a second listener that only
   the operator can reach through an SSH tunnel. The handler has its own
   router, so no public-app route is reachable here. It reads figures from
   `aps.parts.stats` and renders them with `aps.parts.views.console`.

   The SSH tunnel is the only authentication, so there is no login. While
   the tunnel is open, every page in the operator's browser can reach the
   console. Thus the handler refuses a foreign Host header and requires an
   anti-forgery token on every POST. See ADR-0019."
  (:require
   [aps.parts.config :as conf]
   [aps.parts.errors :as errors]
   [aps.parts.stats :as stats]
   [aps.parts.views.console :as views]
   [clojure.string :as str]
   [reitit.ring :as ring]
   [ring.middleware.defaults :refer [site-defaults wrap-defaults]])
  (:import
   (java.time LocalDate OffsetDateTime ZoneOffset)))

;;; Middleware

(def ^:private local-hosts #{"localhost" "127.0.0.1" "[::1]"})

(defn- local-host?
  "Returns true when the Host header of `request` names this machine, on
   any port."
  [request]
  (-> (get-in request [:headers "host"] "")
      str/lower-case
      (str/replace #":\d+$" "")
      local-hosts
      boolean))

;; The operator chooses the local end of the tunnel, so any port is
;; accepted. A DNS rebinding attack arrives with the attacker's host name.
(defn- wrap-local-host
  [handler]
  (fn [request]
    (if (local-host? request)
      (handler request)
      {:status  403
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body    "Forbidden"})))

;; The session exists only to hold the anti-forgery token, so the default
;; in-memory store is enough. Browsers do not separate cookies by port, so
;; the cookie name must differ from the cookie name of the app.
(def ^:private console-defaults
  (-> site-defaults
      (assoc-in [:session :cookie-name] "parts-console")
      (assoc-in [:session :cookie-attrs] {:http-only true :same-site :strict})
      (assoc-in [:security :anti-forgery] true)))

;; The console has no format middleware. A map body comes from
;; `errors/exception`, so it is an error and is sent as plain text.
(defn- wrap-content-type
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (map? (:body response))
        (-> response
            (update :body pr-str)
            (assoc-in [:headers "Content-Type"] "text/plain; charset=utf-8"))
        (update-in response [:headers "Content-Type"]
                   #(or % "text/html; charset=utf-8"))))))

;;; Stats

(defn- stats-page
  [_request]
  (let [today (LocalDate/now ZoneOffset/UTC)]
    {:status 200
     :body   (views/page
              {:fleet (stats/fleet today)
               :users (stats/user-activity today)
               :today today
               :now   (OffsetDateTime/now ZoneOffset/UTC)
               :env   (conf/get-environment)})}))

;;; Handler

(defn handler
  "Returns the Ring handler of the Operator console."
  []
  (-> (ring/ring-handler
       (ring/router [["/" {:get #'stats-page}]]
                    {:data {:middleware [errors/exception]}})
       (ring/create-default-handler))
      wrap-content-type
      (wrap-defaults console-defaults)
      wrap-local-host))
