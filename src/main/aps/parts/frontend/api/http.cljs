(ns aps.parts.frontend.api.http
  "Low level functions for interacting with the backend."
  (:require
   [aps.parts.frontend.api.utils :as utils]
   [cljs-http.client :as http-client]
   [cljs.core.async :refer [<! go]]))

(defn- add-auth-header
  "Add the Authorization header to REQ if OPTS includes :auth-header; if not
  leave REQ untouched."
  [req opts]
  (if-let [header (:auth-header opts)]
    (assoc-in req [:headers "Authorization"] header)
    req))

;; These functions wrap the cljs-http requests.
;; They should not be used directly.
(defn- raw-GET [endpoint params & [opts]]
  (go (<! (http-client/get (str "/api" endpoint)
                           (-> {:query-params params
                                :accept       :transit+json}
                               (add-auth-header opts))))))

(defn- raw-POST [endpoint data & [opts]]
  (go (<! (http-client/post (str "/api" endpoint)
                            (-> {:transit-params data
                                 :accept         :transit+json}
                                (add-auth-header opts))))))

(defn- raw-PUT [endpoint data & [opts]]
  (go (<! (http-client/put (str "/api" endpoint)
                           (-> {:transit-params data
                                :accept         :transit+json}
                               (add-auth-header opts))))))

(defn- raw-PATCH [endpoint data & [opts]]
  (go (<! (http-client/patch (str "/api" endpoint)
                             (-> {:transit-params data
                                  :accept         :transit+json}
                                 (add-auth-header opts))))))

(defn- raw-DELETE [endpoint & [opts]]
  (go (<! (http-client/delete (str "/api" endpoint)
                              (-> {:accept :transit+json}
                                  (add-auth-header opts))))))

(defn wrap-auth
  "Transparently handles auth token lifecycle for HTTP requests.

  Wraps HTTP handlers with JWT auth machinery that:
  1. Augments outbound reqs with auth headers (when token exists)
  2. Intercepts 401s and attempts token refresh
  3. Retries failed reqs with fresh credentials

  The initial call and the post-refresh retry share a single call shape
  (`call-with`), parameterised over which tokens to derive the auth header
  from. There is exactly one place that assembles the handler call, so the
  retry cannot drift from the original — the two paths only differ in their
  `tokens` input.

  OPTIONS:
    :skip-auth - bypasses ALL auth logic (both header injection and refresh)"
  [handler]
  (fn [endpoint params & [opts]]
    (go
      (let [skip-auth? (:skip-auth opts)
            call-with  (fn [tokens]
                         (let [h (when-not skip-auth? (utils/auth-header tokens))]
                           (handler endpoint
                                    params
                                    (cond-> opts h (assoc :auth-header h)))))
            tokens     (utils/get-tokens)
            resp       (<! (call-with tokens))]
        (if (and (= 401 (:status resp))
                 (not skip-auth?)
                 (:refresh_token tokens))
          (let [refresh-resp (<! (raw-POST "/auth/refresh"
                                           {:refresh_token (:refresh_token tokens)}))]
            (if (= 200 (:status refresh-resp))
              (do
                (utils/save-tokens (:body refresh-resp))
                (<! (call-with (utils/get-tokens))))
              resp))
          resp)))))

;; Wrapping the raw HTTP handlers in the auth middleware
;; Pass {:skip-auth true} to ignore authorization flows.
(def GET (wrap-auth raw-GET))
(def POST (wrap-auth raw-POST))
(def PUT (wrap-auth raw-PUT))
(def PATCH (wrap-auth raw-PATCH))
(def DELETE (wrap-auth raw-DELETE))
