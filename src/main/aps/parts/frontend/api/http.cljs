(ns aps.parts.frontend.api.http
  "Thin HTTP layer over cljs-http for the Parts Transit API.

   Auth is an httpOnly session cookie (ADR-0007): the browser attaches it to
   every same-origin request automatically — there is no token handling
   here. Mutating requests additionally carry the `X-CSRF-Token` header,
   which ring's anti-forgery middleware validates against the session."
  (:require
   [aps.parts.frontend.api.utils :as utils]
   [cljs-http.client :as http-client]
   [cljs.core.async :refer [<! go]]))

(defn- csrf-headers
  "Anti-forgery header for a mutating request — the token from the app
   shell's `<meta>` tag. Empty when no token is present."
  []
  (if-let [token (utils/get-csrf-token)]
    {"X-CSRF-Token" token}
    {}))

;; All five accept an optional trailing opts arg — ignored, kept so existing
;; call sites that still pass one keep working.

(defn GET [endpoint & [params _opts]]
  (go (<! (http-client/get (str "/api" endpoint)
                           {:query-params params
                            :accept       :transit+json}))))

(defn POST [endpoint data & [_opts]]
  (go (<! (http-client/post (str "/api" endpoint)
                            {:transit-params data
                             :accept         :transit+json
                             :headers        (csrf-headers)}))))

(defn PUT [endpoint data & [_opts]]
  (go (<! (http-client/put (str "/api" endpoint)
                           {:transit-params data
                            :accept         :transit+json
                            :headers        (csrf-headers)}))))

(defn PATCH [endpoint data & [_opts]]
  (go (<! (http-client/patch (str "/api" endpoint)
                             {:transit-params data
                              :accept         :transit+json
                              :headers        (csrf-headers)}))))

(defn DELETE [endpoint & [_opts]]
  (go (<! (http-client/delete (str "/api" endpoint)
                              {:accept  :transit+json
                               :headers (csrf-headers)}))))
