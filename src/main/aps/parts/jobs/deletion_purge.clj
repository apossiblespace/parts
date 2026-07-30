(ns aps.parts.jobs.deletion-purge
  "Background job that hard-deletes accounts whose 30-day grace window has
   expired. Mirrors the core.async pattern used by `schedule-token-cleanup`
   in `aps.parts.server`."
  (:require
   [aps.parts.db :as db]
   [aps.parts.db.erasure :as erasure]
   [aps.parts.entity.user :as user]
   [clojure.core.async :as async]
   [com.brunobonacci.mulog :as mulog]))

(def ^:private interval-ms
  "How often to scan for accounts past the grace window. Hourly is plenty —
   the 30-day window dwarfs any practical scheduling jitter."
  (* 60 60 1000))

(defn run-once!
  "Purge every account whose grace window has elapsed, each through
   `user/delete!` — the right-to-erasure path, which owns the ordering
   invariant of releasing the account's Stripe link before the purge. A
   Stripe failure postpones that account to the next hourly run, with its
   link still on record. Returns the number of accounts purged; one
   failure doesn't block the others."
  []
  (let [pending (erasure/pending-deletions db/datasource)]
    (reduce
     (fn [purged user-id]
       (try
         (if (:deleted (user/delete! user-id))
           (inc purged)
           purged)
         (catch Exception e
           (let [data (ex-data e)]
             (mulog/log ::purge-error
                        :user-id user-id
                        :error (.getMessage e)
                        :error-type (or (:type data) (.getName (class e)))
                        :error-status (:status data)
                        :error-path (:path data)))
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
    ;; The tick does blocking Stripe HTTP per linked account, so it runs
    ;; on a dedicated thread (async/thread), never on a core.async
    ;; dispatch thread — a slow Stripe would otherwise starve every go
    ;; block in the process.
    (async/thread (tick))
    (async/go-loop []
      (let [timeout-ch (async/timeout interval-ms)
            [_ ch]     (async/alts! [stop-ch timeout-ch])]
        (when (not= ch stop-ch)
          (async/<! (async/thread (tick)))
          (recur))))
    stop-ch))
