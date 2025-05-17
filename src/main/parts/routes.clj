(ns parts.routes
  "Route definitions and middleware configuration"
  (:require
   [muuntaja.core :as muuntaja]
   [reitit.coercion.spec :as rcs]
   [reitit.ring.coercion :as rrc]
   [reitit.ring.middleware.muuntaja :as muuntaja-middleware]

   [parts.middleware :as middleware]
   [parts.api.account :as api.account]
   [parts.api.auth :as api.auth]
   [parts.api.systems :as api.systems]

   [parts.handlers.pages :as pages]
   [parts.handlers.waitlist :as waitlist]))

;; NOTE: What is coercion and how does it work?
;;
;; Coercion is Reitit's system for validating and transforming data at API boundaries.
;; It validates request parameters and responses against specifications (using spec),
;; transforms data types, and makes validated parameters easily accessible.
;;
;; Key components:
;; - :coercion rcs/coercion           - Configures spec-based coercion for routes
;; - coerce-exceptions-middleware     - Handles validation errors with proper responses
;; - coerce-request-middleware        - Validates & transforms incoming request data
;; - coerce-response-middleware       - Validates outgoing response data
;; - :parameters declarations         - Define the shape of expected data
;;
;; Workflow:
;; 1. Parameters extracted from path, query, body, headers
;; 2. Parameters validated against specs
;; 3. Validated parameters organized in request as {:parameters {:path {...}, :query {...}}}
;; 4. Handlers access validated params with destructuring: [{:keys [parameters]}]
;; 5. Responses validated against :responses specs
;;
;; Example:
;; ["/systems/:id"
;;   {:parameters {:path {:id string?}           ; URL path parameters
;;                 :query {:include boolean?}    ; Query string parameters
;;                 :body {:name string?}}        ; Request body structure
;;    :responses {200 {:body map?}               ; Success response
;;                404 {:body {:error string?}}}  ; Error response
;;   :handler get-system}]
;;
;; Benefits:
;; - Declarative API contract in route definitions
;; - Automatic type conversion and validation
;; - Self-documenting routes
;; - Standardized error handling
;; - Focus handlers on business logic, not validation

(def transit-format
  "A configured Muuntaja instance that restricts API format negotiation to
  Transit only (application/transit+json), and transforms Clojure data
  structures to/from Transit format. The default format is explicitly set to
  Transit, so requests without an Accept header vill receive transit responses."
  (-> muuntaja/default-options
      (update :formats select-keys ["application/transit+json"])
      (assoc :default-format "application/transit+json")
      muuntaja/create))

(defn routes
  "Application routes with hierarchical middleware configuration.

  This defines all routes for the application with their associated middleware,
  content types, and handlers. Routes are organized into:

  1. HTML routes - Server-rendered pages with HTML defaults
  2. API routes - Transit-formatted data endpoints with JWT authentication

  Each route type uses a different middleware stack tailored to its requirements.
  The middleware configuration uses Reitit's data-driven approach where:

  - Common middleware is applied at the router level
  - Route-type middleware is applied at the group level (/api)
  - Route-specific middleware is applied to individual routes as needed"
  []
  [;; HTML Routes
   ;;
   ;; These routes return server-rendered HTML content with:
   ;; - anti-forgery protection where forms are present
   ;; - Ring's site-defaults (without sessions)
   ;; - Proper HTML content-type and string conversion

   ;; A form is present on the homepage, so we apply CSRF protection
   ["/" {:middleware [middleware/wrap-html-defaults
                      middleware/wrap-html-response]
         :get {:handler pages/home-page}}]

   ["/playground" {:middleware [middleware/wrap-html-defaults
                                middleware/wrap-html-response]
                   :get {:handler pages/playground}}]

   ["/up" {:get {:handler (fn [_] {:status 200 :body "OK"})}}]

   ;; Form submission endpoint with CSRF protection
   ["/waitlist-signup" {:middleware [middleware/wrap-html-defaults
                                     middleware/wrap-html-response]
                        :post {:handler waitlist/signup}}]

   ;; API Routes
   ;;
   ;; All API routes share these characteristics:
   ;; - Transit-formatted request/response bodies (muuntaja)
   ;; - Parameter coercion and validation (spec-based), see note at the top of this
   ;;   namespace
   ;; - JWT authentication (with some endpoints requiring auth)
   ;; - Security headers appropriate for API endpoints
   ;;
   ;; It's important for the format-middleware to be called last for proper
   ;; conversion.
   ["/api" {:middleware [middleware/wrap-api-defaults
                         rrc/coerce-exceptions-middleware
                         rrc/coerce-request-middleware
                         rrc/coerce-response-middleware
                         middleware/wrap-jwt-authentication
                         muuntaja-middleware/format-middleware]
            :muuntaja transit-format
            :coercion rcs/coercion}

    ["/ping" {:get {:handler (fn [_] {:status 200 :body {:message "Pong!"}})}}]

    ["/auth"
     ["/login" {:post {:handler api.auth/login}}]
     ["/refresh" {:post {:handler api.auth/refresh}}]
     ["/logout" {:post {:middleware [middleware/jwt-auth]
                        :handler api.auth/logout}}]]

    ["/account"
     ["/register" {:post {:handler api.account/register-account}}]
     ["" {:middleware [middleware/jwt-auth]
          :get {:handler api.account/get-account}
          :patch {:handler api.account/update-account}
          :delete {:handler api.account/delete-account}}]]

    ["/systems"
     ["" {:get {:handler api.systems/list-systems}
          :post {:handler api.systems/create-system}}]

     ;; This uses coercion for the `parameters`, see the note at the top of this
     ;; namespace.
     ["/:id" {:parameters {:path {:id string?}}}
      ["" {:get {:handler api.systems/get-system}
           :put {:handler api.systems/update-system}
           :delete {:handler api.systems/delete-system}}]
      ["/pdf" {:get {:handler api.systems/export-pdf}}]
      ["/changes" {:post {:handler api.systems/process-changes}}]]]]])
