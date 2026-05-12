(ns aps.parts.jobs.deletion-purge
  "Background job that hard-deletes accounts whose 30-day grace window has
   expired. Mirrors the core.async pattern used by `schedule-token-cleanup`
   in `aps.parts.server`."
  (:require
   [aps.parts.db :as db]
   [aps.parts.db.erasure :as erasure]
   [clojure.core.async :as async]
   [com.brunobonacci.mulog :as mulog]))

(def ^:private interval-ms
  "How often to scan for accounts past the grace window. Hourly is plenty —
   the 30-day window dwarfs any practical scheduling jitter."
  (* 60 60 1000))

(defn run-once!
  "Purge every account whose grace window has elapsed. Returns the number of
   accounts purged. Each purge runs in its own transaction; one failure
   doesn't block the others."
  []
  (let [pending (erasure/pending-deletions db/datasource)]
    (reduce
     (fn [purged user-id]
       (try
         (erasure/purge-account! db/datasource user-id)
         (mulog/log ::purge-success :user-id user-id)
         (inc purged)
         (catch Exception e
           (mulog/log ::purge-error
                      :user-id user-id
                      :error (.getMessage e)
                      :error-type (.getName (class e)))
           purged)))
     0
     pending)))

(defn schedule!
  "Start the deletion-purge loop. Returns a stop channel; close it to halt."
  []
  (let [stop-ch (async/chan)
        tick    (fn []
                  (try
                    (let [n (run-once!)]
                      (when (pos? n)
                        (mulog/log ::purge-batch-complete :purged n)))
                    (catch Exception e
                      (mulog/log ::purge-batch-error
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
