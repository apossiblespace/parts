(ns aps.parts.jobs.session-cleanup
  "Hourly sweep of expired ephemera: auth_sessions and password_resets
   rows, and idle in-memory rate-limit buckets. Correctness never depends
   on it (reads filter on expires_at; a full bucket equals a fresh one) —
   it only stops dead entries accumulating. Runs on the shared interval
   scaffold (`aps.parts.jobs.scheduling`)."
  (:require
   [aps.parts.auth.session-store :as session-store]
   [aps.parts.db :as db]
   [aps.parts.jobs.scheduling :as scheduling]
   [aps.parts.password-resets :as password-resets]
   [aps.parts.ratelimit :as ratelimit]
   [com.brunobonacci.mulog :as mulog]))

(def ^:private interval-ms (* 60 60 1000))

(defn schedule!
  "Start the cleanup loop. Returns a stop channel; close it to halt."
  []
  ;; Each sweep gets its own try: a DB outage failing the two table
  ;; sweeps must not starve the in-memory bucket prune — the only bound
  ;; on the rate-limit atom.
  (let [sweep! (fn [event f]
                 (try
                   (let [n (f)]
                     (when (pos? n)
                       (mulog/log event :removed n)))
                   (catch Exception e
                     (mulog/log ::sweep-error
                                :sweep event
                                :error (.getMessage e)
                                :error-type (.getName (class e))))))]
    (scheduling/schedule-every!
     interval-ms
     (fn []
       (sweep! ::rate-limit-buckets-pruned ratelimit/prune-idle!)
       (sweep! ::sessions-swept #(session-store/delete-expired! db/datasource))
       (sweep! ::password-resets-swept #(password-resets/delete-expired! db/datasource))))))
