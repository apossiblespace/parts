(ns aps.parts.alerts
  "Operator error-alert sink: emails the operator the first time something in
   the alert allowlist throws, throttled so a crash-loop can't flood the inbox.

   Two layers:

   - a pure decision core (`alert-decision`) — allowlist, signature, cooldown,
     prune — which is the whole test surface; and
   - a mulog custom publisher (`AlertPublisher`) that runs that core off the
     request path and, when it says send, dispatches one email over SMTP.

   Deliberately NOT a general mailer. It sends to a single operator address
   using the operator's own SMTP credentials, and must never grow into the
   foundation for user-facing transactional email (password resets et al. —
   that is a separate, future module, TASK-014). Keeping the seam here means a
   password-reset path never depends on the operator's personal mail account."
  (:require
   [clojure.pprint :as pprint]
   [com.brunobonacci.mulog :as mulog]
   [com.brunobonacci.mulog.buffer :as mulog-buffer]
   [postal.core :as postal]))

;; =============================================================================
;; Pure decision core — no clock, no socket, no mulog. The entire test surface.

(def default-cooldown-ms
  "How long a given error signature stays suppressed after one alert. Long
   enough that a crash-loop collapses to a single email; short enough that a
   recurring-but-intermittent fault re-surfaces the same day."
  (* 15 60 1000))

(def alert-events
  "mulog event names that warrant waking the operator. Mirrors the events
   logged in `aps.parts.errors`; lives here, not there, so the alerting policy
   stays with the alerting module rather than leaking into the error handlers."
  #{:aps.parts.errors/unhandled-exception
    :aps.parts.errors/batch-failure
    :aps.parts.errors/postgres-exception})

(defn event-signature
  "A stable cooldown key for an event. Prefers a non-value discriminator —
   sql-state, then error-class — over the free-text message, so a crash-loop of
   one fault collapses to a single email while distinct faults stay distinct.
   The message is avoided as a key because for postgres it embeds the offending
   row value and is no longer logged."
  [event]
  [(:mulog/event-name event)
   (or (:sql-state event) (:error-class event) (:error event))])

(defn- prune
  "Drop cooldown entries last sent before `now - cooldown-ms`, bounding the map
   to signatures seen within the current window."
  [state now cooldown-ms]
  (let [cutoff (- now cooldown-ms)]
    (into {} (remove (fn [[_ sent]] (< sent cutoff))) state)))

(defn alert-decision
  "Pure throttling core. Given the cooldown `state` (signature -> last-sent ms),
   a mulog `event`, and `cooldown-ms`, decide whether to alert.

   Returns `{:send? bool, :signature sig-or-nil, :state next-state}`.
   Non-allowlisted events never send and leave `state` untouched. The event's
   own `:mulog/timestamp` is the clock, so the core has no time dependency."
  [state event cooldown-ms]
  (if-not (contains? alert-events (:mulog/event-name event))
    {:send? false :signature nil :state state}
    (let [now       (:mulog/timestamp event)
          sig       (event-signature event)
          pruned    (prune state now cooldown-ms)
          last-sent (get pruned sig)
          send?     (or (nil? last-sent) (>= (- now last-sent) cooldown-ms))]
      {:send?     send?
       :signature sig
       :state     (if send? (assoc pruned sig now) pruned)})))

;; =============================================================================
;; Side-effecting shell — SMTP send + the mulog custom publisher.

(defn- postal-connection
  "The postal connection map for an SMTP config. The transport flag follows the
   submission port: 587 is STARTTLS (connect plaintext, then upgrade → `:tls`),
   465 (and anything else) is implicit SSL from connect (`:ssl`). Pairing the
   wrong flag with a port yields a plaintext attempt the server rejects. This
   rule lives here, beside `postal/send-message`, rather than in config — it's
   postal's vocabulary, not the operator's."
  [{:keys [host port user pass]}]
  (assoc {:host host :port port :user user :pass pass}
         (if (= 587 port) :tls :ssl) true))

(def ^:private alert-body-keys
  "Allowlist of event fields included in an alert email. Deliberately small: an
   alert must never carry clinical content into an operator inbox, so the body is
   built from these structural fields only — never a full-event dump."
  [:mulog/event-name :mulog/timestamp :mulog/namespace
   :error :error-class :cause-type :sql-state :diagnostics :failing-change])

(defn- alert-body
  "The alert email body: the allowlisted structural fields of `event`,
   pretty-printed — never the whole event."
  [event]
  (with-out-str (pprint/pprint (select-keys event alert-body-keys))))

(defn- alert-message
  "The postal message map for `event`. Subject carries the deployment domain so
   a staging test error is unmistakable from a prod incident in the inbox."
  [{:keys [smtp domain]} event]
  {:from    (:from smtp)
   :to      (:to smtp)
   :subject (str "[parts-alert][" domain "] " (name (:mulog/event-name event)))
   :body    (alert-body event)})

(defn- send-alert!
  "Send one alert email. Any failure is swallowed and logged — alerting must
   never crash the publisher loop or the app. The `::alert-send-failed` event is
   not in the allowlist, so it can't trigger a further email; it just lands in
   journald for the pull path."
  [{:keys [smtp] :as config} event]
  (try
    (postal/send-message (postal-connection smtp) (alert-message config event))
    (catch Exception e
      (mulog/log ::alert-send-failed :error (.getMessage e)))))

(deftype AlertPublisher [buffer state config]
  com.brunobonacci.mulog.publisher.PPublisher
  (agent-buffer [_] buffer)
  (publish-delay [_] 200)
  (publish [_ buf]
    (doseq [event (map second (mulog-buffer/items buf))]
      (let [{:keys [send?] :as decision} (alert-decision @state event (:cooldown-ms config))]
        (reset! state (:state decision))
        (when send? (send-alert! config event))))
    (mulog-buffer/clear buf)))

(defn publisher
  "mulog custom-publisher factory (referenced by :fqn-function). `config` is the
   publisher map from `start-publisher!`; it carries `:smtp` (the operator's
   SMTP facts — host/port/user/pass/to/from), `:domain`, and `:cooldown-ms`."
  [config]
  (->AlertPublisher (mulog-buffer/agent-buffer 10000) (atom {}) config))
