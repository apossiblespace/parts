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

(defn legal-content-dir
  "Filesystem directory holding the operator's legal documents
   (privacy.md / terms.md / dpa.md), read at runtime, or nil. Unset in dev and
   CI — the bundled example templates are served instead. Production sets it via
   PARTS__LEGAL__CONTENT_DIR."
  []
  (l-config/get config :legal/content-dir))

(defn render-font-dir
  "Filesystem directory holding the PDF document fonts, or nil. The Nix
   dev shell exports PARTS__RENDER__FONT_DIR (dev and CI); hosts install
   the pinned files and set the variable — see the runbook. Unlike the
   legal templates there is no fallback; `aps.parts.render.fonts` fails
   fast and documents why."
  []
  (l-config/get config :render/font-dir))

(defn smtp-config
  "The shared SMTP relay credentials (`PARTS__SMTP__*`) — host, port, user,
   password. Nothing here is committed: this repo is public, and the relay is
   an operational detail (Scaleway TEM in production, ADR-0016).

   Returns nil unless host, user and password are all present, so everything
   that sends mail stays off until deliberately configured, on any host.
   `:port` defaults to 465.

   Credentials only — who mail goes to and from is the consumers' business
   (`alert-config`, `mail-from`). The postal transport flag (implicit SSL vs
   STARTTLS, which depends on the port) is likewise derived by the layers
   that own an SMTP client — config never speaks postal's vocabulary."
  []
  (let [host (l-config/get config :smtp/host)
        user (l-config/get config :smtp/user)
        pass (l-config/get config :smtp/password)]
    (when (and host user pass)
      {:host host
       :port (parse-port (or (l-config/get config :smtp/port) 465))
       :user user
       :pass pass})))

(defn alert-config
  "Operator error-alert settings: the relay credentials plus the alert
   recipient (`PARTS__ALERT__TO`) and sender (`PARTS__ALERT__FROM`, defaulting
   to `PARTS__MAIL__FROM`, then the SMTP user). The last fallback only suits
   relays whose user is an address — on Scaleway TEM it is a bare project id,
   which the relay rejects as a sender. Returns nil unless the relay is
   configured *and* a recipient is set — creds alone must not switch alerting
   on."
  []
  (when-let [smtp (smtp-config)]
    (when-let [to (l-config/get config :alert/to)]
      (assoc smtp
             :to to
             :from (or (l-config/get config :alert/from)
                       (l-config/get config :mail/from)
                       (:user smtp))))))

(defn stripe-secret-key
  "Just the Stripe API secret (`PARTS__STRIPE__SECRET_KEY`), independent
   of the full self-serve set below: erasure must release a linked
   customer even when the rest of the Stripe env (webhook secret, price
   ids) is absent or mid-rotation. Nil when unset."
  []
  (l-config/get config :stripe/secret-key))

(defn stripe-config
  "Self-serve billing settings (`PARTS__STRIPE__*`): the API secret — use
   a restricted key (rk_), not a full secret key, with four scopes:
   Checkout Sessions: Write, Billing Portal: Write, Customers: Write
   (erasure deletes the linked Customer), and Subscriptions: Read (the
   checkout webhook fetches the subscription to grant the real paid
   period; without it every completed checkout 500s and retries) — the
   webhook signing secret, and the two subscription price ids (one
   Product, two Prices: monthly and yearly).

   Returns nil unless all four are present, so self-serve billing stays off
   until deliberately configured. Nothing here is committed: this repo is
   public."
  []
  (let [secret-key     (stripe-secret-key)
        webhook-secret (l-config/get config :stripe/webhook-secret)
        price-monthly  (l-config/get config :stripe/price-monthly)
        price-yearly   (l-config/get config :stripe/price-yearly)]
    (when (and secret-key webhook-secret price-monthly price-yearly)
      {:secret-key     secret-key
       :webhook-secret webhook-secret
       :prices         {:monthly price-monthly
                        :yearly  price-yearly}})))

(defn mail-from
  "The From for transactional mail (`PARTS__MAIL__FROM`), e.g.
   `Gosha <gosha@ifs.tools>` — an address on the TEM-verified sending domain
   (ADR-0016). Nil when unset; `aps.parts.mail` refuses to send without it."
  []
  (l-config/get config :mail/from))

(defn mail-reply-to
  "Reply-To for transactional mail that should reach a human
   (`PARTS__MAIL__REPLY_TO`) — the operator's personal address, keeping the
   concierge reply promise while sending from the verified domain. Nil when
   unset; consumers omit the header then."
  []
  (l-config/get config :mail/reply-to))

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

(defn assert-db-topology!
  "Fail fast on the one combination that silently ships credentials and PII
   in cleartext: production, TLS off, and a non-loopback DB host. Loopback
   without TLS is the deliberate deployment shape (postgres shares the app's
   box); the moment :db/host points elsewhere, PARTS__DB__SSL must be true."
  [{:keys [host ssl prod?]}]
  (when (and prod?
             (not ssl)
             (not (contains? #{nil "localhost" "127.0.0.1" "::1"} host)))
    (throw (ex-info "Refusing cleartext postgres connection to a remote host in prod; set PARTS__DB__SSL=true"
                    {:type :config :db/host host})))
  true)

(defn database-config
  "Get complete database configuration map suitable for next.jdbc."
  []
  (let [host (l-config/get config :db/host)
        ssl  (parse-bool (l-config/get config :db/ssl))]
    (assert-db-topology! {:host host :ssl ssl :prod? (prod?)})
    {:dbtype   "postgresql"
     :host     host
     :port     (l-config/get config :db/port)
     :dbname   (l-config/get config :db/name)
     :user     (l-config/get config :db/user)
     :password (l-config/get config :db/password)
     :ssl      ssl}))

(defn client-ip-header
  "The header carrying the proxy-vouched client IP, lower-cased for Ring
   lookup. :ratelimit/client-ip-header (PARTS__RATELIMIT__CLIENT_IP_HEADER),
   default \"x-real-ip\" as set by the generated Caddyfiles. Override only if
   the trusted edge changes (e.g. a CDN in front of Caddy)."
  []
  (-> (or (l-config/get config :ratelimit/client-ip-header) "x-real-ip")
      cstr/lower-case))

(def ^:private printable-config-keys
  "Config keys whose values may appear in the startup table. Everything
   else prints <redacted>: a new key is treated as secret until deliberately
   named here, instead of leaking until its name happens to match a
   substring heuristic."
  #{:env
    :db/type :db/host :db/port :db/name :db/user :db/ssl
    :http/host :http/port :http/protocol
    :app/base-url
    :legal/content-dir :render/font-dir
    :repl/socket :repl/port :repl/host
    :ratelimit/client-ip-header
    :launch/launched?
    :smtp/host :smtp/port
    :alert/to :alert/from
    :stripe/price-monthly :stripe/price-yearly})

(defn print-config-table
  "Print all accessed configuration keys, values, and sources as a table.
   Values are redacted unless the key is in `printable-config-keys`."
  []
  (let [cached @(:values config)
        rows   (for [[k v] (sort-by key cached)]
                 {:key    k
                  :value  (if (contains? printable-config-keys k)
                            (pr-str (:val v))
                            "<redacted>")
                  :source (-> (:source v)
                              str
                              (cstr/replace #"^file:" "")
                              (cstr/replace #".*/resources/" "resources/"))})]
    (if (seq rows)
      (pprint/print-table [:key :value :source] rows)
      (println "No configuration values have been accessed yet."))))

