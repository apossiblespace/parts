(ns aps.parts.helpers.browser
  "Drives the full app like a browser: a session cookie carried between
   requests, the anti-forgery token from the page, and Transit API calls
   as the SPA sends them."
  (:require
   [muuntaja.core :as muuntaja]
   [ring.mock.request :as mock]))

(def ^:private transit "application/transit+json")

(defn browse
  "Sends `request` to `app` with the session cookie `cookie`. Returns
   `[response cookie]`, where `cookie` is the new one when the response
   sets it."
  [app request cookie]
  (let [response   (app (cond-> request cookie (mock/header "cookie" cookie)))
        set-cookie (get-in response [:headers "Set-Cookie"])]
    [response (or (some #(re-find #"parts-session=[^;]+" %)
                        (if (string? set-cookie) [set-cookie] set-cookie))
                  cookie)]))

(defn csrf-token
  "Returns the anti-forgery token in `body`, from the app shell's meta tag
   or from a form's hidden field."
  [body]
  (or (some->> (re-find #"<meta[^>]*name=\"csrf-token\"[^>]*>" body)
               (re-find #"content=\"([^\"]+)\"")
               second)
      (some->> (re-find #"<input[^>]*name=\"__anti-forgery-token\"[^>]*>" body)
               (re-find #"value=\"([^\"]+)\"")
               second)))

(defn anonymous-session
  "Loads the app shell like a new visitor. Returns `[cookie token]`."
  [app]
  (let [[response cookie] (browse app (mock/request :get "/app/login") nil)]
    [cookie (csrf-token (str (:body response)))]))

(defn api
  "An API request as the SPA sends it: Transit, with the anti-forgery
   token, and a Transit `body` when given."
  [method path token & [body]]
  (cond-> (-> (mock/request method path)
              (mock/header "accept" transit)
              (mock/header "x-csrf-token" token))
    body (-> (mock/body (slurp (muuntaja/encode transit body)))
             (mock/content-type transit))))

(defn decode
  "The response body as data. Error bodies arrive as plain maps: the
   exception middleware runs outside the Transit encoding."
  [response]
  (let [body (:body response)]
    (if (map? body) body (muuntaja/decode transit body))))

(defn sign-in
  "Signs `user` in with `password` from a fresh visit. Returns `[cookie
   token]` of the signed-in session."
  [app user password]
  (let [[cookie token] (anonymous-session app)
        [_ cookie]     (browse app (api :post "/api/auth/login" token
                                        {:email (:email user) :password password})
                               cookie)]
    [cookie token]))
