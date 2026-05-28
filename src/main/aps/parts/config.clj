(ns aps.parts.config
  (:require
   [clojure.pprint :as pprint]
   [clojure.string :as cstr]
   [lambdaisland.config :as l-config]))

;; Expose config directly for consumer access
;; Use (l-config/get config :key) to access configuration values
(def config
  (l-config/create {:prefix "parts"}))

(defn get-environment
  "Get the current environment. lambdaisland/config determines this from:
  1. parts.env Java system property
  2. PARTS__ENV environment variable
  3. CI=true defaults to :test
  4. Falls back to :dev"
  []
  (:env config))

(defn prod?
  "Are we in the PRODUCTION environment?"
  []
  (= (get-environment) :prod))

(defn test?
  "Are we in the TEST environment?"
  []
  (= (get-environment) :test))

(defn dev?
  "Are we in the DEVELOPMENT environment?"
  []
  (= (get-environment) :dev))

(defn- parse-port
  "Coerce a port value to a long.

   lambdaisland/config does no type coercion: env-var values
   (PARTS__HTTP__PORT) come back as strings, while config.edn defaults come
   back as numbers. This normalises both so callers get a consistent type."
  [v]
  (if (string? v)
    (Long/parseLong v)
    v))

(defn http-port
  "The port the HTTP server binds to — :http/port resolved from the
   PARTS__HTTP__PORT env var, or the config.edn default."
  []
  (parse-port (l-config/get config :http/port)))

(defn parse-bool
  "Coerce a config value to a boolean.

   Like `parse-port`, this exists because lambdaisland/config does no type
   coercion. A value set via an env var (e.g. PARTS__LAUNCH__LAUNCHED_QMARK_)
   arrives as a string, while a config.edn default arrives as a real
   boolean. Without coercion the string \"false\" is truthy — so a flag
   meant to be off reads as on."
  [v]
  (if (string? v)
    (Boolean/parseBoolean v)
    (boolean v)))

(defn base-url
  "The canonical public base URL, with no trailing slash — e.g.
   https://parts.ifs.tools. Unlike `host-uri` (the internal bind address,
   which behind a reverse proxy is not externally reachable), this is the
   origin to use when building links that are sent to users."
  []
  (l-config/get config :app/base-url))

(defn app-domain
  "Just the host portion of `base-url`, without scheme, port, or path —
   e.g. `parts.ifs.tools` (prod) or `parts-dev.ifs.tools` (the dev
   deploy, via `PARTS__APP__BASE_URL`). Derived from `base-url` so a
   per-environment URL automatically flows to consumers that want only
   the bare domain (the Plausible analytics tag is the first such
   consumer; cookie-domain, future analytics, etc. could reuse it)."
  []
  (.getHost (java.net.URI/create (base-url))))

(defn smtp-config
  "Operator error-alert SMTP settings, read entirely from the environment
   (`PARTS__SMTP__*` / `PARTS__ALERT__*`). Nothing here is committed: this repo
   is public, and the mail host and operator address are operational details.

   Returns nil unless host, user, password and recipient are all present — so
   alerting stays off until deliberately configured, on any host. `:from`
   defaults to the SMTP user; `:port` defaults to 465.

   These are operator facts only. The postal transport flag (implicit SSL vs
   STARTTLS, which depends on the port) is derived by the alerting layer
   (`aps.parts.alerts`), which owns the SMTP client — config never speaks
   postal's vocabulary."
  []
  (let [host (l-config/get config :smtp/host)
        user (l-config/get config :smtp/user)
        pass (l-config/get config :smtp/password)
        to   (l-config/get config :alert/to)]
    (when (and host user pass to)
      {:host host
       :port (parse-port (or (l-config/get config :smtp/port) 465))
       :user user
       :pass pass
       :to   to
       :from (or (l-config/get config :alert/from) user)})))

(def ^:private dev-session-key
  "The session key shipped in config.edn for local development. Production
   must override it; `session-key` refuses to run on this value in prod."
  "dev-session-key0")

(defn session-key
  "The 16-byte secret that encrypts the auth-session cookie (ADR-0007),
   resolved from `:session/key` (set via the PARTS__SESSION__KEY env var).

   Throws if the key is missing, not exactly 16 bytes, or — in production —
   still the committed dev default. A misconfigured session key must fail
   loudly: silently running on a guessable secret would let anyone forge a
   session cookie. Rotating the key invalidates every active session."
  []
  (let [k (l-config/get config :session/key)]
    (when (and (prod?) (or (nil? k) (= k dev-session-key)))
      (throw (ex-info "PARTS__SESSION__KEY must be set in production to a non-default value"
                      {:type :config-error})))
    (when (or (nil? k) (not= 16 (count (.getBytes ^String k "UTF-8"))))
      (throw (ex-info "PARTS__SESSION__KEY must be exactly 16 bytes"
                      {:type :config-error})))
    k))

(defn host-uri
  "Get the full qualified application host URI, eg: http://localhost:3000"
  []
  (str (l-config/get config :http/protocol)
       "://"
       (l-config/get config :http/host)
       ":"
       (http-port)))

(defn database-config
  "Get complete database configuration map suitable for next.jdbc."
  []
  {:dbtype   "postgresql"
   :host     (l-config/get config :db/host)
   :port     (l-config/get config :db/port)
   :dbname   (l-config/get config :db/name)
   :user     (l-config/get config :db/user)
   :password (l-config/get config :db/password)
   :ssl      (l-config/get config :db/ssl)})

(def ^:private secret-key-substrings
  #{"password" "secret" "token" "key"})

(defn- secret-key?
  "True if the key's name suggests it holds a secret value that should not
   appear in logs (e.g. :db/password, :session/key)."
  [k]
  (when-let [n (some-> k name cstr/lower-case)]
    (boolean (some #(cstr/includes? n %) secret-key-substrings))))

(defn print-config-table
  "Print all accessed configuration keys, values, and sources as a table.
   Values for keys matching `secret-key?` are redacted."
  []
  (let [cached @(:values config)
        rows   (for [[k v] (sort-by key cached)]
                 {:key    k
                  :value  (if (secret-key? k)
                            "<redacted>"
                            (pr-str (:val v)))
                  :source (-> (:source v)
                              str
                              (cstr/replace #"^file:" "")
                              (cstr/replace #".*/resources/" "resources/"))})]
    (if (seq rows)
      (pprint/print-table [:key :value :source] rows)
      (println "No configuration values have been accessed yet."))))

