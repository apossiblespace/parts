(ns aps.parts.ratelimit
  "In-process per-IP rate limiting for the unauthenticated, abuse-prone
   endpoints (login, register, invite redemption).

   A token bucket per [route-key, client-ip]: `capacity` is the burst a single
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

(defn limiter
  "Reitit middleware that token-buckets requests per client IP under
   `route-key`. opts:
     :capacity         burst size (default 10)
     :refill-per-ms    tokens added per millisecond (default 10/60000 = 10/min)
     :now-ms           clock thunk (default System/currentTimeMillis; for tests)
     :store            buckets atom (default the shared module atom; for tests)
     :client-ip-header trusted client-IP header (default conf/client-ip-header)"
  [route-key {:keys [capacity refill-per-ms now-ms store client-ip-header]
              :or   {capacity      10
                     refill-per-ms (/ 10.0 60000)
                     now-ms        #(System/currentTimeMillis)
                     store         buckets}}]
  (let [header (or client-ip-header (conf/client-ip-header))]
    (fn [handler]
      (fn [request]
        (let [k [route-key (client-ip request header)]
              b (-> (swap! store update k step (now-ms) capacity refill-per-ms)
                    (get k))]
          (if (:allowed? b)
            (handler request)
            too-many-response))))))
