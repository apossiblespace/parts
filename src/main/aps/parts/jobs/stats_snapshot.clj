(ns aps.parts.jobs.stats-snapshot
  "Hourly write of the `stats_daily` rows for the growth charts of the
   Operator console (`stats/snapshot!`). The first run after midnight UTC
   writes the day that ended; the other runs find nothing to write. The
   first run ever back-fills every day since the first account."
  (:require
   [aps.parts.jobs.scheduling :as scheduling]
   [aps.parts.stats :as stats]
   [com.brunobonacci.mulog :as mulog]))

(def ^:private interval-ms (* 60 60 1000))

(defn schedule!
  "Start the snapshot loop. Returns a stop channel; close it to halt."
  []
  (scheduling/schedule-every!
   interval-ms
   (fn []
     (try
       (let [n (stats/snapshot!)]
         (when (pos? n)
           (mulog/log ::days-written :days n)))
       (catch Exception e
         (mulog/log ::snapshot-error
                    :error (.getMessage e)
                    :error-type (.getName (class e))))))))
