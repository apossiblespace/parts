(ns aps.parts.auth.middleware
  "Ring middleware that enforces authentication and authorization on routes
   — the plumbing half of the auth package. `aps.parts.auth` holds the auth
   logic (the session backend, `current-user-id`) this depends on."
  (:require
   [aps.parts.auth :as auth]
   [aps.parts.billing :as billing]
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

(defn- read-request? [request]
  (#{:get :head :options} (:request-method request)))

(defn- allowed-when-read-only?
  "True when the matched route accepts writes from a read-only Map or
   account (its route data has `:allow-when-read-only?`)."
  [request]
  (get-in request [:reitit.core/match :data :allow-when-read-only?]))

(defn wrap-editing-allowed
  "Middleware for the Map routes. A lapsed account is read-only
   (ADR-0021): every request that is not a read is rejected as
   `:account-lapsed` (402), except on a route whose data has
   `:allow-when-read-only?` (archiving and unarchiving). Reads, exports
   and everything outside the Map routes (the Account page, billing) are
   never blocked."
  [handler]
  (fn [request]
    (if (or (read-request? request)
            (allowed-when-read-only? request)
            (billing/can-edit? (auth/current-user-id request)))
      (handler request)
      (throw (ex-info "Your account is read-only. Subscribe to keep editing; you can still view and export your Maps."
                      {:type :account-lapsed})))))

(defn wrap-map-access
  "Middleware for routes scoped to a single Map. The Map must exist and
   be owned by the authenticated user, or the request is rejected as
   `:not-found` (404).

   An archived Map is read-only: every request that is not a read is
   rejected as `:map-archived` (409), except on a route whose data has
   `:allow-when-read-only?` (archiving and unarchiving)."
  [handler]
  (fn [request]
    (let [user-id (auth/current-user-id request)
          map-id  (get-in request [:parameters :path :id])
          the-map (parts-map/fetch-identity map-id)]
      (cond
        (not (and the-map (owns-map? user-id the-map)))
        (throw (ex-info "Map not found" {:type :not-found :id map-id}))

        (and (:archived_at the-map)
             (not (read-request? request))
             (not (allowed-when-read-only? request)))
        (throw (ex-info "This Map is archived. Unarchive it to make changes."
                        {:type :map-archived :id map-id}))

        :else
        (handler request)))))
