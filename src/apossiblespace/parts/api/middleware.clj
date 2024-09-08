(ns apossiblespace.parts.api.middleware
  (:require [reitit.ring.middleware.exception :as exception]
            [com.brunobonacci.mulog :as mulog]
            [clojure.string :as str])
  (:import (org.sqlite SQLiteException)))

(defn exception-handler
  [message status]
  (fn [^Exception _e _request]
    {:status status
     :body {:error message}}))

(defn sqlite-constraint-violation-handler
  [^SQLiteException e _request]
  (let [error-message (.getMessage e)]
    (mulog/log ::sqlite-exception :error error-message)
    {:status 409
     :body {:error (cond
                     (str/includes? error-message "UNIQUE constraint failed")
                     "A resource with this unique identifier already exists"

                     (str/includes? error-message "CHECK constraint failed")
                     "The provided data does not meet the required constraints"

                     (str/includes? error-message "FOREIGN KEY constraint failed")
                     "The referenced resource does not exist"

                     :else "A database constraint was violated")}}))

(def exception
  (exception/create-exception-middleware
   (merge
    exception/default-handlers
    {;; ex-info with :type :validation
     :validation (exception-handler "Invalid data" 400)

     ;; SQLite exceptions
     SQLiteException sqlite-constraint-violation-handler

     ;; Default
     ::exception/default
     (fn [^Exception e _request]
       (mulog/log ::unhandled-exception :error (.getMessage e))
       {:status 500
        :body {:error "Internal server error"}})
     })))
