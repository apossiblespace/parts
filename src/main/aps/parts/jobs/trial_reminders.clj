(ns aps.parts.jobs.trial-reminders
  "Hourly run of `trial-reminders/send-due!`. The send log makes a run
   that finds nothing to send cost two queries."
  (:require
   [aps.parts.jobs.scheduling :as scheduling]
   [aps.parts.trial-reminders :as trial-reminders]
   [com.brunobonacci.mulog :as mulog]))

(def ^:private interval-ms (* 60 60 1000))

(defn schedule!
  "Starts the reminder loop. Returns a stop channel. Close it to halt the
   loop."
  []
  (scheduling/schedule-every!
   interval-ms
   (fn []
     (try
       (let [n (trial-reminders/send-due!)]
         (when (pos? n)
           (mulog/log ::reminders-sent :count n)))
       (catch Exception e
         (mulog/log ::reminders-error
                    :error (.getMessage e)
                    :error-type (.getName (class e))))))))
