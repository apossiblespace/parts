(ns aps.parts.jobs.scheduling
  "The one interval-loop scaffold for background jobs: run `tick` now and
   then every interval, always on a real thread — job ticks do blocking
   work (JDBC, Stripe HTTP) and must never occupy a core.async dispatch
   thread, where they would starve every go block in the process."
  (:require
   [clojure.core.async :as async]))

(defn schedule-every!
  "Run `tick` (a zero-arg fn that must catch its own exceptions) now and
   every `interval-ms` thereafter. Returns a stop channel; close it to
   halt the loop."
  [interval-ms tick]
  (let [stop-ch (async/chan)]
    (async/thread (tick))
    (async/go-loop []
      (let [timeout-ch (async/timeout interval-ms)
            [_ ch]     (async/alts! [stop-ch timeout-ch])]
        (when (not= ch stop-ch)
          (async/<! (async/thread (tick)))
          (recur))))
    stop-ch))
