(ns aps.parts.auth.middleware
  "Ring middleware that enforces authentication and authorization on routes
   — the plumbing half of the auth package. `aps.parts.auth` holds the auth
   logic (the session backend, `current-user-id`) this depends on."
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.db :as db]
   [aps.parts.entity.map :as parts-map]
   [buddy.auth :refer [authenticated?]]
   [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
   [ring.util.response :as response]))

(defn wrap-session-auth
  "Lift the auth session into `request[:identity]` via buddy's session
   backend, so handlers and `require-auth` can see who is signed in. A route
   with this applied has an authentication status that can be checked."
  [handler]
  (-> handler
      (wrap-authentication auth/backend)
      (wrap-authorization auth/backend)))

(defn require-auth
  "Middleware ensuring a route is only accessible to authenticated users."
  [handler]
  (fn [request]
    (if (authenticated? request)
      (handler request)
      (-> (response/response {:error "Unauthorized"})
          (response/status 401)))))

(defn- owns-map?
  "True when `user-id` (the session-identity `:sub` string) owns `the-map`."
  [user-id the-map]
  (= (db/->uuid user-id) (:owner_id the-map)))

(defn wrap-map-access
  "Middleware for routes scoped to a single Map. The Map must exist and
   be owned by the authenticated user, or the request is rejected as
   `:not-found` (404)."
  [handler]
  (fn [request]
    (let [user-id (auth/current-user-id request)
          map-id  (get-in request [:parameters :path :id])
          the-map (parts-map/fetch-identity map-id)]
      (if (and the-map (owns-map? user-id the-map))
        (handler request)
        (throw (ex-info "Map not found" {:type :not-found :id map-id}))))))
