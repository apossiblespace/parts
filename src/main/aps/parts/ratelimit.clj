(ns aps.parts.ratelimit
  "In-process rate limiting: per client IP on the unauthenticated,
   abuse-prone endpoints (login, register, invite redemption), per user id
   on the authenticated write endpoints (map creation, change batches).

   A token bucket per [route-key, identity]: `capacity` is the burst a single
   client may make back-to-back, then requests are allowed at `refill-per-ms`.
   State lives in a module-level atom on purpose — the reitit router is rebuilt
   per request (see aps.parts.server), so state held in a middleware instance
   would reset every request and limit nothing.

   Single-server, in-memory, no external store. The client identity is a
   single proxy-set header (default X-Real-IP) that the edge proxy overwrites
   on every request — never X-Forwarded-For, which is client-appendable and
   whose chain length differs per route, so no fixed position in it is
   reliably the client. See docs/runbook.md, 'Rate limiting & the trusted
   client IP'."
  (:require
   [aps.parts.common.utils :refer [normalize-email]]
   [aps.parts.config :as conf]
   [clojure.string :as str]))

(defonce ^:private buckets (atom {}))

(defn- client-ip
  "The bucketing identity: the proxy-vouched header, else :remote-addr. The
   fallback collapses all clients into one bucket — over-throttling, never a
   bypass — the safe failure mode if the proxy stops setting the header."
  [request header]
  (or (some-> (get-in request [:headers header]) str/trim not-empty)
      (:remote-addr request)))

(defn step
  "Pure token-bucket transition. Given the prior `bucket` (or nil), the current
   time `now-ms`, the `capacity`, and `refill-per-ms`, returns the next bucket
   with `:allowed?` set for this request. A fresh bucket starts full."
  [bucket now-ms capacity refill-per-ms]
  (let [{:keys [tokens last-ms] :or {tokens capacity last-ms now-ms}} bucket
        refilled                                                      (min (double capacity)
                                                                           (+ tokens (* (- now-ms last-ms) refill-per-ms)))
        allowed?                                                      (>= refilled 1)]
    {:tokens   (if allowed? (- refilled 1) refilled)
     :last-ms  now-ms
     :allowed? allowed?}))

(def ^:private too-many-response
  {:status  429
   :headers {"Content-Type" "text/plain" "Retry-After" "60"}
   :body    "Too many requests. Please slow down and try again shortly."})

(defn- limit-by
  "Token-bucket middleware keyed by [route-key (identity-fn request)]. opts:
     :capacity      burst size (default 10)
     :refill-per-ms tokens added per millisecond (default 10/60000 = 10/min)
     :now-ms        clock thunk (default System/currentTimeMillis; for tests)
     :store         buckets atom (default the shared module atom; for tests)"
  [route-key identity-fn {:keys [capacity refill-per-ms now-ms store]
                          :or   {capacity      10
                                 refill-per-ms (/ 10.0 60000)
                                 now-ms        #(System/currentTimeMillis)
                                 store         buckets}}]
  (fn [handler]
    (fn [request]
      (let [k [route-key (identity-fn request)]
            b (-> (swap! store update k step (now-ms) capacity refill-per-ms)
                  (get k))]
        (if (:allowed? b)
          (handler request)
          too-many-response)))))

(defn limiter
  "Reitit middleware that token-buckets requests per client IP under
   `route-key`. opts: see `limit-by`, plus
     :client-ip-header trusted client-IP header (default conf/client-ip-header)"
  [route-key {:keys [client-ip-header] :as opts}]
  (let [header (or client-ip-header (conf/client-ip-header))]
    (limit-by route-key #(client-ip % header) opts)))

(defn user-limiter
  "Reitit middleware that token-buckets requests per authenticated user id —
   for session-authenticated write routes, where identity (not a possibly
   NAT-shared client IP) is the right key. Sits inside require-auth so
   :identity is present; if it ever isn't, all such requests share one
   bucket — over-throttling, never a bypass. opts: see `limit-by`."
  [route-key opts]
  (limit-by route-key
            (fn [request] (or (get-in request [:identity :sub]) "anonymous"))
            opts))

(defn form-email-limiter
  "Reitit middleware that token-buckets requests per submitted form `email`
   — for endpoints that send mail to an attacker-chosen address (password
   reset requests), where a per-IP bucket alone still lets one client flood
   a victim's inbox and burn sender reputation. Keys on the normalized
   address whether or not an account exists, so being limited reveals
   nothing. Must sit after form-params parsing in the middleware chain; a
   request without an email shares one bucket — over-throttling, never a
   bypass. opts: see `limit-by`."
  [route-key opts]
  (limit-by route-key
            (fn [request]
              (or (normalize-email (get-in request [:form-params "email"]))
                  "missing"))
            opts))

(def ^:private prune-idle-ms
  "How long a bucket may sit untouched before the sweep drops it. Every
   configured limiter refills to full well within a day, and a full bucket
   is indistinguishable from a fresh one — so dropping day-idle entries
   changes no limiting decision."
  (* 24 60 60 1000))

(defn prune-idle!
  "Drop day-idle buckets; returns the number removed. Needed since
   `form-email-limiter` keys on attacker-chosen input, which would grow
   the module atom for the life of the process. Called hourly by the
   cleanup job."
  ([] (prune-idle! (System/currentTimeMillis)))
  ([now-ms]
   (let [cutoff    (- now-ms prune-idle-ms)
         [old new] (swap-vals! buckets
                               (fn [m]
                                 (into {}
                                       (remove (fn [[_ b]] (< (:last-ms b) cutoff)))
                                       m)))]
     (- (count old) (count new)))))
