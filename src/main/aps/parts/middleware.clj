(ns aps.parts.middleware
  (:require
   [buddy.auth :refer [authenticated?]]
   [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
   [clojure.string :as str]
   [com.brunobonacci.mulog :as mulog]
   [aps.parts.auth :as auth]
   [reitit.ring.middleware.exception :as exception]
   [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.defaults :refer [api-defaults wrap-defaults site-defaults]]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.util.response :as response])
  (:import
   (org.sqlite SQLiteException)))

(defn- exception-handler
  "Generic exceptions handler used by the `exception` middleware.

  Sets the response status to the provided `status`, and sets the response
  message to the error message retrieved from the exception, or, failing that,
  to the `message` provided."
  [message status]
  (fn [^Exception e _request]
    (let [error-message (.getMessage e)]
      {:status status
       :body {:error (or error-message message)}})))

(def sqlite-errors
  "A map containing substrings of a SQLiteException error message, and the
  corresponding user friendly error message."
  {"UNIQUE constraint failed" "A resource with this unique identifier already exists"
   "CHECK constraint failed" "The provided data does not meet the required constraints"
   "NOT NULL constraint failed" "A required field was missing"
   "FOREIGN KEY constraint failed" "The referenced resource does not exist"})

(defn sqlite-constraint-violation-handler
  "Handler for SQLite-specific exceptions.

  If the error message includes a string that is a key in `sqlite-errors`, the
  error message will be the corresponding value; otherwise a generic message."
  [^SQLiteException e _request]
  (let [error-message (.getMessage e)]
    (mulog/log ::sqlite-exception :error error-message)
    {:status 409
     :body {:error (or (some
                        (fn [[k, v]] (when (str/includes? error-message k) v))
                        sqlite-errors)
                       "A database constraint was violated")}}))

(def exception
  "Middleware handling exceptions. Combines the default exception handlers from
  Reitit with cutom handlers. New custom handlers should be added to this
  function."
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
        :body {:error "Internal server error"}})})))

(defn logging
  "Middleware logging each incoming request with minimal information.

  This middleware is called before Muuntaja converts the input into clojure
  objects, so to `:body` value is a `java.io.InputStream`, which is not easily
  inspectable.  Later in the lifecycle, Muuntaja will insert a parsed body under
  the `:parsed-params` key."
  [handler]
  (fn [request]
    (let [user-id (get-in request [:identity :sub])
          request-info {:uri (:uri request)
                        :request-method (:request-method request)
                        :query-params (:query-params request)
                        :remote-addr (:remote-addr request)
                        :user-agent (get-in request [:headers "user-agent"])}
          authenticated? (boolean user-id)]
      (mulog/log ::request :info request-info :authenticated? authenticated? :user-id user-id)
      (handler request))))

(defn wrap-jwt-authentication
  "Middleware adding JWT authentication to a route. A route with this middleware
  applied will have an authentication status which can be validated against."
  [handler]
  (-> handler
      (wrap-authentication auth/backend)
      (wrap-authorization auth/backend)))

(defn jwt-auth
  "Middleware ensuring a route is only accessible to authenticated users. "
  [handler]
  (fn [request]
    (if (authenticated? request)
      (handler request)
      (-> (response/response {:error "Unauthorized"})
          (response/status 401)))))

(defn wrap-html-defaults
  "Middleware that applies a set of Ring defaults for HTML routes.
  Among other things, this middleware enables:

  - Anti-forgery protection
  - Sessions (necessary for CSRF tokens to work)

  We disable wrapping static resources here because these resources need to be
  available for all routes, and this middleware is set for certain routes only.
  FIXME: This might be troublesome, review middleware order.

  And other defaults that are not immediately relevant to us. See
  `site-defaults` for the full details."
  [handler]
  (wrap-defaults
   handler
   (-> site-defaults
       (assoc :static false))))

;; TODO: Investigate whether Content Security Policy is needed:
;;  - https://developer.mozilla.org/en-US/docs/Web/HTTP/CSP
;;  - https://cheatsheetseries.owasp.org/cheatsheets/Content_Security_Policy_Cheat_Sheet.html
(defn wrap-api-defaults
  "Apply default middleware for API routes.

  This uses standard `api-defaults` (not secure-api-defaults) as the base
  configuration, making it suitable for applications behind a reverse proxy that
  handles SSL termination.

  Security enhancements:
  - Sets X-Frame-Options to SAMEORIGIN to prevent clickjacking attacks
  - Sets X-Content-Type-Options to nosniff to prevent MIME type sniffing
  - Enables X-XSS-Protection with mode=block to activate browser XSS filters

  Notable omissions:
  - No HTTPS enforcement (handled by reverse proxy)
  - No Strict-Transport-Security header (better set at proxy level)
  - No Content-Security-Policy"
  [handler]
  (wrap-defaults
   handler
   (-> api-defaults
       (assoc-in [:security :frame-options] :sameorigin)
       (assoc-in [:security :content-type-options] :nosniff)
       (assoc-in [:security :xss-protection] {:mode :block}))))

(defn wrap-core-middlewares
  "Apply essential Ring middleware for the entire application.
  - `wrap-resource`: Serves static files from resources/public
  - `wrap-content-type`: Sets proper MIME types based on file extensions,
    ensuring SVG files are served as image/svg+xml instead of text/plain"
  [handler]
  (-> handler
      (wrap-resource "public")
      (wrap-content-type)))

(defn wrap-html-response
  "Middleware for properly formatting HTML responses.

  This middleware performs two essential transformations for HTML routes:

  1. It ensures the response body is a string by calling `str` on it.  This
  handles various body types like Hiccup structures or other Clojure data that
  should be rendered as HTML.

  2. It sets the Content-Type header to 'text/html; charset=utf-8' if no
  Content-Type is already specified, ensuring browsers properly interpret the
  response.

  This middleware only processes responses that:
  - Are proper Ring response maps
  - Don't already have a Content-Type header set

  Usage: Apply this middleware to routes that return HTML content.  It's an
  alternative to using Muuntaja for HTML formatting, which is more appropriate
  for data formats rather than presentation formats."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (and (map? response)
               (not (get-in response [:headers "Content-Type"])))
        (-> response
            (update :body str)
            (assoc-in [:headers "Content-Type"] "text/html; charset=utf-8"))
        response))))

(def anti-forgery
  "Anti-forgery middleware for HTML forms, see docs on `wrap-anti-forgery`"
  wrap-anti-forgery)
