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
   [aps.parts.common.change-event :as change-event]
   [aps.parts.entity.part :as part]
   [aps.parts.entity.relationship :as relationship]
   [next.jdbc :as jdbc]))

(defmulti process-change
  "Apply a single canonical change-event (see `aps.parts.common.change-event`).
   Takes a context map (`{:system-id ... :actor-id ... :tx ...}`) plus the event.
   Throws on failure; the surrounding transaction rolls back."
  (fn [_ctx event]
    [(:entity event) (:type event)]))

(defmethod process-change [:part :create]
  [{:keys [system-id actor-id tx]} {:keys [id data]}]
  (let [part-data (assoc data :id id :system_id system-id)]
    {:success true :result (part/create! part-data actor-id tx)}))

(defmethod process-change [:part :update]
  [{:keys [actor-id tx]} {:keys [id data]}]
  {:success true :result (part/update! id data actor-id tx)})

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

;; -- Transport-agnostic batch entry point ---------------------------------

(defn- as-batch-failure
  "Re-tag an exception raised while applying a batch as a `:batch-failure`,
   keeping the original `:type` as `:cause-type` and recording the bad change."
  [t failing-change]
  (ex-info (ex-message t)
           (-> (ex-data t)
               (assoc :type       :batch-failure
                      :cause-type (:type (ex-data t)))
               (update :failing-change #(or % failing-change)))
           t))

(defn apply-changes!
  "Apply a batch of changes in one transaction (all-or-nothing).

   `changes` is untrusted wire input — a single change-event map or a vector;
   `change-event/parse` coerces and validates it before any DB work. Returns a
   vector of per-change `{:success true :result ...}` maps. Any failure, parse-
   time or process-time, propagates as a `:batch-failure` `ex-info` carrying
   `:failing-change`, and the transaction rolls back."
  [ds {:keys [system-id actor-id changes]}]
  (let [parsed (try
                 (change-event/parse changes)
                 (catch Throwable t
                   (throw (as-batch-failure t nil))))]
    (jdbc/with-transaction [tx ds]
      (let [ctx {:system-id system-id :actor-id actor-id :tx tx}]
        (mapv (fn [change]
                (try
                  (process-change ctx change)
                  (catch Throwable t
                    (throw (as-batch-failure t change)))))
              parsed)))))
