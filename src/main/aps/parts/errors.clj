(ns aps.parts.errors
  "Turning thrown things into HTTP responses — the reitit exception
   middleware and its handlers, including the PostgreSQL constraint-error
   mapping."
  (:require
   [com.brunobonacci.mulog :as mulog]
   [reitit.ring.middleware.exception :as exception])
  (:import
   (org.postgresql.util PSQLException)))

(defn- exception-handler
  "Generic exceptions handler used by the `exception` middleware.

  Sets the response status to the provided `status`, and sets the response
  message to the error message retrieved from the exception, or, failing that,
  to the `message` provided."
  [message status]
  (fn [^Exception e _request]
    (let [error-message (.getMessage e)]
      {:status status
       :body   {:error (or error-message message)}})))

(def postgres-sql-state-errors
  "A map of PostgreSQL SQL state codes to user-friendly error messages."
  {"23505" "A resource with this unique identifier already exists" ; unique violation
   "23514" "The provided data does not meet the required constraints" ; check constraint
   "23502" "A required field was missing" ; not null violation
   "23503" "The referenced resource does not exist"}) ; foreign key violation

(defn safe-error-fields
  "Non-clinical diagnostics for an exception, safe to log and alert. Returns a
   flat map: always an `:error-class`; for a PSQLException also `:sql-state` and
   the constraint/table/column NAMES (schema metadata, never row values). Never
   the exception message or a constraint Detail, which embed the offending
   value. nil-safe."
  [^Throwable t]
  (when t
    (if (instance? PSQLException t)
      (let [^PSQLException e t
            sem              (.getServerErrorMessage e)]
        (cond-> {:error-class "PSQLException"
                 :sql-state   (.getSQLState e)}
          sem (assoc :constraint (.getConstraint sem)
                     :table      (.getTable sem)
                     :column     (.getColumn sem))))
      {:error-class (.getName (class t))})))

(defn postgres-constraint-violation-handler
  "Handler for PostgreSQL-specific exceptions.

  Uses SQL state codes to determine the type of constraint violation and
  provide user-friendly error messages."
  [^PSQLException e _request]
  ;; Log sql-state + constraint/table/column NAMES (schema metadata, under
  ;; :diagnostics). The PSQLException message and its Detail embed the offending
  ;; row value (which may be clinical content) and must never reach the logs or
  ;; the alert email — only the response, to the therapist over TLS, carries a
  ;; user-facing message.
  (let [safe (safe-error-fields e)]
    (mulog/log ::postgres-exception
               :sql-state   (:sql-state safe)
               :error-class (:error-class safe)
               :diagnostics safe))
  {:status 409
   :body   {:error (or (get postgres-sql-state-errors (.getSQLState e))
                       "A database constraint was violated")}})

(defn redact-change
  "A failing change-event reduced to non-clinical identifiers, for logging and
   alerts. Drops `:data` (a Part's label/description/body_location/notes),
   keeping only the entity, type, and id so an operator can locate a failure
   without seeing clinical content. nil-safe."
  [change]
  (when change
    (select-keys change [:entity :type :id])))

(def exception
  "Middleware handling exceptions. Combines Reitit's default exception
   handlers with custom ones; new custom handlers go in the handler map
   below."
  (exception/create-exception-middleware
   (merge
    exception/default-handlers
    {;; ex-info with :type :validation
     :validation
     (exception-handler "Invalid data" 400)

     :not-found
     (exception-handler "Resource not found" 404)

     ;; Optimistic-lock failure in the bitemporal write path: the entity was
     ;; superseded by a concurrent change between read and write.
     :conflict
     (exception-handler "Entity was modified by a concurrent change" 409)

     ;; A change in a batch threw; the whole batch was rolled back. The
     ;; ex-data carries `:failing-change` so the client can highlight it.
     :batch-failure
     (fn [^Exception e _request]
       (let [data (ex-data e)
             safe (safe-error-fields (.getCause e))]
         ;; Log structural identifiers + safe error metadata only — never the
         ;; failing change's :data or the exception message, which carry a Part's
         ;; clinical content. `:cause-type` and the cause's safe fields preserve
         ;; *why* it failed. The response, to the therapist over TLS, still
         ;; carries the full message and change for the client UI.
         (mulog/log ::batch-failure
                    :failing-change (redact-change (:failing-change data))
                    :cause-type     (:cause-type data)
                    :sql-state      (:sql-state safe)
                    :error-class    (:error-class safe)
                    :diagnostics    safe)
         {:status 422
          :body   {:error          (or (.getMessage e) "Batch change failed")
                   :failing_change (:failing-change data)}}))

     ;; PostgreSQL exceptions
     PSQLException
     postgres-constraint-violation-handler

     ;; Default
     ::exception/default
     (fn [^Exception e _request]
       (mulog/log ::unhandled-exception
                  :error       (.getMessage e)
                  :error-class (.getName (class e)))
       {:status 500
        :body   {:error "Internal server error"}})})))
