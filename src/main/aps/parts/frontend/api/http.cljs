(ns aps.parts.frontend.api.http
  "Thin HTTP layer over cljs-http for the Parts Transit API.

   Auth is an httpOnly session cookie (ADR-0007): the browser attaches it to
   every same-origin request automatically — there is no token handling
   here. Mutating requests additionally carry the `X-CSRF-Token` header,
   which ring's anti-forgery middleware validates against the session."
  (:require
   [aps.parts.frontend.api.utils :as utils]
   [cljs-http.client :as http-client]
   [cljs.core.async :refer [<! chan go put!]]
   [cognitect.transit :as transit]))

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

(def ^:private keepalive-limit
  "Largest keepalive request body, in bytes, that browsers accept."
  (* 64 1024))

(defn POST-keepalive
  "Starts `POST` as a keepalive `fetch`, which the browser completes even if
   the page closes or is suspended. Returns a channel with `{:status n}`,
   where 0 is a network error, or nil when the body is over
   `keepalive-limit`."
  [endpoint data]
  (let [body (transit/write (transit/writer :json) data)]
    (when (<= (.-size (js/Blob. #js [body])) keepalive-limit)
      (let [result (chan 1)]
        (-> (js/fetch (str "/api" endpoint)
                      #js {:method    "POST"
                           :keepalive true
                           :headers   (clj->js (merge {"Content-Type" "application/transit+json"
                                                       "Accept"       "application/transit+json"}
                                                      (csrf-headers)))
                           :body      body})
            (.then #(put! result {:status (.-status ^js %)}))
            (.catch #(put! result {:status 0})))
        result))))
