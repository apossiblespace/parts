(ns aps.parts.middleware
  "Request-pipeline middleware: request logging, the launch gate, the
   ring-defaults wrappers, static-resource serving, and HTML response
   formatting.

   Authentication/authorization middleware lives in
   `aps.parts.auth.middleware`; exception→response handling in
   `aps.parts.errors`."
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.launch :as launch]
   [com.brunobonacci.mulog :as mulog]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.defaults :refer [api-defaults wrap-defaults site-defaults]]
   [ring.middleware.resource :refer [wrap-resource]]))

(defn logging
  "Middleware logging each incoming request with minimal information.

  This middleware is called before Muuntaja converts the input into clojure
  objects, so to `:body` value is a `java.io.InputStream`, which is not easily
  inspectable.  Later in the lifecycle, Muuntaja will insert a parsed body under
  the `:parsed-params` key."
  [handler]
  (fn [request]
    (let [user-id        (auth/current-user-id request)
          request-info   {:uri            (:uri request)
                          :request-method (:request-method request)
                          :query-params   (:query-params request)
                          :remote-addr    (:remote-addr request)
                          :user-agent     (get-in request [:headers "user-agent"])}
          authenticated? (boolean user-id)]
      (mulog/log ::request :info request-info :authenticated? authenticated? :user-id user-id)
      (handler request))))

(defn wrap-launch-gated
  "Middleware that hides a route behind the `aps.parts.launch/launched?` flag.
   When the toggle is off, throws a `:not-found` ex-info so the standard
   exception handler renders the same 404 shape as other not-found errors."
  [handler]
  (fn [request]
    (if (launch/launched?)
      (handler request)
      (throw (ex-info "Not found" {:type :not-found})))))

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
       (assoc :static false)
       (assoc :session (auth/session-config))
       ;; Replace site-defaults' anti-forgery map (which carries an
       ;; `X-Ring-Anti-Forgery` safe-header bypass) with plain `true` — no
       ;; header bypass on session-establishing endpoints like /invite/:token.
       (assoc-in [:security :anti-forgery] true))))

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
       ;; Cookie auth (ADR-0007): /api shares the one auth session, and
       ;; gains anti-forgery — cookies are auto-sent, so the bearer-token
       ;; CSRF immunity is spent and must be replaced.
       (assoc :session (auth/session-config))
       (assoc-in [:security :anti-forgery] true)
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
