(ns aps.parts.api.systems-events
  "Per-change dispatch + a transport-agnostic batch entry point.

   `apply-changes!` is the function HTTP, WebSocket, or any future transport
   should call. It accepts a context map + change list, runs them in one
   transaction, and returns per-change results. No HTTP types are involved
   here — the transport-specific code (request decoding, response building)
   lives in the caller.

   Atomicity: the batch is all-or-nothing. If any `process-change` throws,
   the surrounding `with-transaction` rolls back the whole batch. Domain
   errors propagate as `ex-info` so the HTTP exception middleware can
   translate them. Unexpected exceptions become 500s. Per-change success
   reporting (the `{:success true ...}` shape) is kept for API stability;
   `:success false` never appears — failures throw instead."
  (:require
   [aps.parts.entity.part :as part]
   [aps.parts.entity.relationship :as relationship]
   [next.jdbc :as jdbc]))

(defmulti process-change
  "Process a single change event based on entity type and operation.
   Takes a context map (`{:system-id ... :actor-id ...}`) plus the event.
   Throws on failure; the surrounding transaction rolls back."
  (fn [_ctx event]
    [(keyword (:entity event)) (keyword (:type event))]))

(defmethod process-change [:part :create]
  [{:keys [system-id actor-id tx]} {:keys [id data]}]
  (let [part-data (assoc data :id id :system_id system-id)]
    {:success true :result (part/create! part-data actor-id tx)}))

(defmethod process-change [:part :update]
  [{:keys [actor-id tx]} {:keys [id data]}]
  {:success true :result (part/update! id data actor-id tx)})

(defmethod process-change [:part :position]
  [{:keys [actor-id tx]} {:keys [id data]}]
  (let [position-data {:position_x (int (:x data))
                       :position_y (int (:y data))}]
    {:success true :result (part/update! id position-data actor-id tx)}))

(defmethod process-change [:part :remove]
  [{:keys [actor-id tx]} {:keys [id]}]
  {:success true :result (part/delete! id actor-id tx)})

(defmethod process-change [:relationship :create]
  [{:keys [system-id actor-id tx]} {:keys [id data]}]
  (let [rel-data (assoc data :id id :system_id system-id)]
    {:success true :result (relationship/create! rel-data actor-id tx)}))

(defmethod process-change [:relationship :update]
  [{:keys [actor-id tx]} {:keys [id data]}]
  {:success true :result (relationship/update! id data actor-id tx)})

(defmethod process-change [:relationship :remove]
  [{:keys [actor-id tx]} {:keys [id]}]
  {:success true :result (relationship/delete! id actor-id tx)})

(defmethod process-change :default
  [_ctx event]
  (throw (ex-info (str "Unknown change type: " (:entity event) "/" (:type event))
                  {:type  :unknown-change-type
                   :event event})))

;; -- Transport-agnostic batch entry point ---------------------------------

(defn apply-changes!
  "Apply a batch of changes inside one transaction (all-or-nothing).

   `ctx` is `{:system-id UUID :actor-id <user-id>}`. `changes` is a vector
   of change-event maps shaped like `{:entity ... :type ... :id ... :data ...}`.

   Returns a vector of per-change result maps `{:success true :result ...}`
   if all succeed. If any change throws, the whole transaction rolls back
   and the exception propagates — callers (HTTP handler, WebSocket frame
   handler) are responsible for translating that into a transport response.
   The exception's `ex-data` carries `:failing-change` so callers can point
   at the specific event that broke the batch."
  [ds {:keys [system-id actor-id changes]}]
  (jdbc/with-transaction [tx ds]
    (let [ctx {:system-id system-id :actor-id actor-id :tx tx}]
      (mapv (fn [change]
              (try
                (process-change ctx change)
                (catch Throwable t
                  (throw (ex-info (.getMessage t)
                                  (assoc (ex-data t)
                                         :type           :batch-failure
                                         :cause-type     (:type (ex-data t))
                                         :failing-change change)
                                  t)))))
            changes))))
