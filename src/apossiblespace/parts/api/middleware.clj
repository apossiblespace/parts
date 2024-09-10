(ns apossiblespace.parts.api.middleware
  (:require [reitit.ring.middleware.exception :as exception]
            [com.brunobonacci.mulog :as mulog]
            [clojure.string :as str])
  (:import (org.sqlite SQLiteException)))

(defn exception-handler
  [message status]
  (fn [^Exception e _request]
    (let [error-message (.getMessage e)]
      {:status status
       :body {:error (or error-message message)}})))

(def sqlite-errors
  {"UNIQUE constraint failed" "A resource with this unique identifier already exists"
   "CHECK constraint failed" "The provided data does not meet the required constraints"
   "NOT NULL constraint failed" "A required field was missing"
   "FOREIGN KEY constraint failed" "The referenced resource does not exist"})

(defn sqlite-constraint-violation-handler
  [^SQLiteException e _request]
  (let [error-message (.getMessage e)]
    (mulog/log ::sqlite-exception :error error-message)
    {:status 409
     :body {:error (or (some
                        (fn [[k, v]] (when (str/includes? error-message k) v))
                        sqlite-errors)
                       "A database constraint was violated")}}))

(def exception
  (exception/create-exception-middleware
   (merge
    exception/default-handlers
    {;; ex-info with :type :validation
     :validation (exception-handler "Invalid data" 400)

     :not-found (exception-handler "Resource not found" 404)

     ;; SQLite exceptions
     SQLiteException sqlite-constraint-violation-handler

     ;; Default
     ::exception/default
     (fn [^Exception e _request]
       (mulog/log ::unhandled-exception :error (.getMessage e))
       {:status 500
        :body {:error "Internal server error"}})
     })))

(defn logging
  [handler]
  (fn [request]
    (let [user-id (get-in request [:identity :sub])
          authenticated? (boolean user-id)]
      (mulog/log ::request :request request, :authenticated? authenticated? :user-id user-id)
      (handler request))))
