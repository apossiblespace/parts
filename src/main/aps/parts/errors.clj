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

(defn postgres-constraint-violation-handler
  "Handler for PostgreSQL-specific exceptions.

  Uses SQL state codes to determine the type of constraint violation and
  provide user-friendly error messages."
  [^PSQLException e _request]
  (let [error-message (.getMessage e)
        sql-state     (.getSQLState e)]
    (mulog/log ::postgres-exception :error error-message :sql-state sql-state)
    {:status 409
     :body   {:error (or (get postgres-sql-state-errors sql-state)
                         "A database constraint was violated")}}))

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

     ;; A change in a batch threw; the whole batch was rolled back. The
     ;; ex-data carries `:failing-change` so the client can highlight it.
     :batch-failure
     (fn [^Exception e _request]
       (let [data (ex-data e)]
         (mulog/log ::batch-failure
                    :error          (.getMessage e)
                    :failing-change (:failing-change data))
         {:status 422
          :body   {:error          (or (.getMessage e) "Batch change failed")
                   :failing_change (:failing-change data)}}))

     ;; PostgreSQL exceptions
     PSQLException
     postgres-constraint-violation-handler

     ;; Default
     ::exception/default
     (fn [^Exception e _request]
       (mulog/log ::unhandled-exception :error (.getMessage e))
       {:status 500
        :body   {:error "Internal server error"}})})))
