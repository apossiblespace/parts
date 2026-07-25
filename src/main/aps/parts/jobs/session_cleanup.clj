(ns aps.parts.jobs.session-cleanup
  "Hourly sweep of expired rows in auth_sessions. Correctness never depends
   on it (the store's reads filter on expires_at) — it only stops dead
   sessions accumulating. Mirrors the deletion-purge job's async pattern."
  (:require
   [aps.parts.auth.session-store :as session-store]
   [aps.parts.db :as db]
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
