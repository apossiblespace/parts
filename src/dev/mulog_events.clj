(ns mulog-events
  (:require
   [com.brunobonacci.mulog :as mulog]
   [com.brunobonacci.mulog.buffer :as mulog-buffer]))

;; ---------------------------------------------------------
;; Set event global context
;; - information added to every event for REPL workflow
;; (mulog/set-global-context! {:app-name "parts"
;;                             :version "0.1.0-SNAPSHOT", :env "dev"})
;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; Mulog event publishing

(deftype TapPublisher
         [buffer transform]
  com.brunobonacci.mulog.publisher.PPublisher
  (agent-buffer [_] buffer)
  (publish-delay [_] 200)
  (publish [_ buffer]
    (doseq [item (transform (map second (mulog-buffer/items buffer)))]
      (tap> item))
    (mulog-buffer/clear buffer)))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn ^:private tap-events
  [{:keys [transform] :as _config}]
  (TapPublisher. (mulog-buffer/agent-buffer 10000) (or transform identity)))

(def tap-publisher
  "Start mulog custom tap publisher to send all events to Portal
  and other tap sources
  `mulog-tap-publisher` to stop publisher"
  (mulog/start-publisher!
   {:type :custom, :fqn-function "mulog-events/tap-events"}))

(defn stop
  "Stop mulog tap publisher to ensure multiple publishers are not started
 Recommended before using `(restart)` or evaluating the `user` namespace"
  []
  tap-publisher)
