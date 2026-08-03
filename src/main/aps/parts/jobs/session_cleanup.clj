(ns aps.parts.jobs.session-cleanup
  "Hourly sweep of expired ephemera: auth_sessions and password_resets
   rows, and idle in-memory rate-limit buckets. Correctness never depends
   on it (reads filter on expires_at; a full bucket equals a fresh one) —
   it only stops dead entries accumulating. Mirrors the deletion-purge
   job's async pattern."
  (:require
   [aps.parts.auth.session-store :as session-store]
   [aps.parts.db :as db]
   [aps.parts.password-resets :as password-resets]
   [aps.parts.ratelimit :as ratelimit]
   [clojure.core.async :as async]
   [com.brunobonacci.mulog :as mulog]))

(def ^:private interval-ms (* 60 60 1000))

(defn schedule!
  "Start the cleanup loop. Returns a stop channel; close it to halt."
  []
  (let [stop-ch (async/chan)
        tick    (fn []
                  (try
                    (let [n (session-store/delete-expired! db/datasource)]
                      (when (pos? n)
                        (mulog/log ::sessions-swept :removed n)))
                    (let [n (password-resets/delete-expired! db/datasource)]
                      (when (pos? n)
                        (mulog/log ::password-resets-swept :removed n)))
                    (let [n (ratelimit/prune-idle!)]
                      (when (pos? n)
                        (mulog/log ::rate-limit-buckets-pruned :removed n)))
                    (catch Exception e
                      (mulog/log ::sweep-error
                                 :error (.getMessage e)
                                 :error-type (.getName (class e))))))]
    (tick)
    (async/go-loop []
      (let [timeout-ch (async/timeout interval-ms)
            [_ ch]     (async/alts! [stop-ch timeout-ch])]
        (when (not= ch stop-ch)
          (tick)
          (recur))))
    stop-ch))
