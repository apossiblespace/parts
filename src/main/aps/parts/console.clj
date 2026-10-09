(ns aps.parts.console
  "Ring handler for the Operator console, the operator's web UI for fleet
   stats and for emails to Users.

   `aps.parts.server` serves this handler on a second listener that only
   the operator can reach through an SSH tunnel. The handler has its own
   router, so no public-app route is reachable here. It reads figures from
   `aps.parts.stats`, builds and sends emails with
   `aps.parts.operator-email`, and renders with `aps.parts.views.console`.

   The SSH tunnel is the only authentication, so there is no login. While
   the tunnel is open, every page in the operator's browser can reach the
   console. Thus the handler refuses a foreign Host header and requires an
   anti-forgery token on every POST. See ADR-0019."
  (:require
   [aps.parts.config :as conf]
   [aps.parts.errors :as errors]
   [aps.parts.operator-email :as operator-email]
   [aps.parts.stats :as stats]
   [aps.parts.views.console :as views]
   [clojure.string :as str]
   [com.brunobonacci.mulog :as mulog]
   [jsonista.core :as json]
   [reitit.ring :as ring]
   [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
   [ring.middleware.session.memory :refer [memory-store]]
   [ring.util.response :as response])
  (:import
   (java.time LocalDate OffsetDateTime ZoneOffset)
   (java.time.format DateTimeFormatter)))

;;; Middleware

(def ^:private local-hosts #{"localhost" "127.0.0.1" "[::1]"})

(defn- local-host?
  "Returns true when the Host header of `request` names this machine, on
   any port."
  [request]
  (-> (get-in request [:headers "host"] "")
      str/lower-case
      (str/replace #":\d+$" "")
      local-hosts
      boolean))

;; The operator chooses the local end of the tunnel, so any port is
;; accepted. A DNS rebinding attack arrives with the attacker's host name.
(defn- wrap-local-host
  [handler]
  (fn [request]
    (if (local-host? request)
      (handler request)
      {:status  403
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body    "Forbidden"})))

;; The session holds the anti-forgery token and the email draft. It must
;; be in memory, because the default store of `site-defaults` is a cookie,
;; and a draft is too large for a cookie. A draft does not need to survive
;; a restart. Browsers do not separate cookies by port, so the cookie name
;; must differ from the cookie name of the app.
(def ^:private console-defaults
  (-> site-defaults
      (assoc-in [:session :store] (memory-store))
      (assoc-in [:session :cookie-name] "parts-console")
      (assoc-in [:session :cookie-attrs] {:http-only true :same-site :strict})
      (assoc-in [:security :anti-forgery] true)))

;; The console has no format middleware. A map body comes from
;; `errors/exception`, so it is an error and is sent as plain text.
(defn- wrap-content-type
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (map? (:body response))
        (-> response
            (update :body pr-str)
            (assoc-in [:headers "Content-Type"] "text/plain; charset=utf-8"))
        (update-in response [:headers "Content-Type"]
                   #(or % "text/html; charset=utf-8"))))))

;;; Email draft

(def ^:private empty-draft {:kind "product-update" :subject "" :body ""})

(defn- lf
  [s]
  (str/replace (or s "") #"\r\n?" "\n"))

;; A browser sends textarea line breaks as CRLF in a form submit but as LF
;; from the preview script. Without one form, the hash of the same draft
;; would differ between the test send and the preview.
(defn- form-draft
  "Returns the draft in the form parameters of `request`, with LF line
   breaks."
  [request]
  (let [{:strs [kind subject body]} (:form-params request)]
    {:kind    (if (operator-email/kinds kind) kind (:kind empty-draft))
     :subject (lf subject)
     :body    (lf body)}))

(defn- composer [request] (get-in request [:session :composer]))

(def ^:private time-format (DateTimeFormatter/ofPattern "HH:mm 'UTC'"))

(defn- test-status
  "Returns `{:tested? :text}`, which tells whether `draft` is the draft of
   the last test send that `composer` records."
  [composer draft]
  (let [{:keys [hash at to]} (:tested composer)]
    (cond
      (nil? hash)
      {:tested? false :text "No test sent yet."}

      (= hash (operator-email/draft-hash draft))
      {:tested? true
       :text    (str "✓ Test sent to " to " at " (.format time-format at)
                     ". It matches this draft.")}

      :else
      {:tested? false
       :text    (str "The draft changed after the test at " (.format time-format at)
                     ". Send a new test.")})))

;;; Pages

(defn- page
  [request]
  (let [today (LocalDate/now ZoneOffset/UTC)
        draft (or (:draft (composer request)) empty-draft)]
    {:status 200
     :body   (views/page
              {:fleet    (stats/fleet today)
               :users    (stats/user-activity today)
               :today    today
               :now      (OffsetDateTime/now ZoneOffset/UTC)
               :env      (conf/get-environment)
               :draft    draft
               :preview  (operator-email/content draft (operator-email/test-unsubscribe-url))
               :status   (test-status (composer request) draft)
               :operator (conf/mail-reply-to)
               :error    (get-in request [:flash :error])})}))

;; Each preview also stores the draft, so a reload of the page keeps it.
(defn- preview
  [request]
  (let [draft   (form-draft request)
        content (operator-email/content draft (operator-email/test-unsubscribe-url))
        status  (test-status (composer request) draft)]
    {:status  200
     :headers {"Content-Type" "application/json; charset=utf-8"}
     :body    (json/write-value-as-string {:html   (:html content)
                                           :text   (:text content)
                                           :status (:text status)
                                           :tested (:tested? status)})
     :session (assoc-in (:session request) [:composer :draft] draft)}))

(defn- send-test
  [request]
  (let [draft    (form-draft request)
        session  (assoc-in (:session request) [:composer :draft] draft)
        redirect (-> (response/redirect "/") (response/status 303) (assoc :session session))]
    (if-let [problem (operator-email/validate draft)]
      (assoc redirect :flash {:error problem})
      (try
        (let [to (operator-email/send-test! draft)]
          (assoc-in redirect [:session :composer :tested]
                    {:hash (operator-email/draft-hash draft)
                     :at   (OffsetDateTime/now ZoneOffset/UTC)
                     :to   to}))
        (catch Exception e
          (mulog/log ::test-send-failed :error (ex-message e) :type (:type (ex-data e)))
          (assoc redirect :flash {:error (str "The test email was not sent. "
                                              (or (ex-message e) (.getName (class e))))}))))))

;;; Handler

(defn handler
  "Returns the Ring handler of the Operator console."
  []
  (-> (ring/ring-handler
       (ring/router [["/" {:get #'page}]
                     ["/preview" {:post #'preview}]
                     ["/test" {:post #'send-test}]]
                    {:data {:middleware [errors/exception]}})
       (ring/create-default-handler))
      wrap-content-type
      (wrap-defaults console-defaults)
      wrap-local-host))
