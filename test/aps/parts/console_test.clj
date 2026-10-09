(ns aps.parts.console-test
  (:require
   [aps.parts.config :as conf]
   [aps.parts.console :as console]
   [aps.parts.db :as db]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [aps.parts.operator-email :as operator-email]
   [aps.parts.server :as server]
   [aps.parts.stats :as stats]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [jsonista.core :as jsonista]
   [ring.mock.request :as mock]))

(use-fixtures :each with-test-db)

(defn- with-host [request host]
  (assoc-in request [:headers "host"] host))

(deftest test-host-allowlist
  (let [app (console/handler)]
    (testing "serves the page to a local host on any port"
      (doseq [host ["localhost" "localhost:9100" "127.0.0.1:3100" "[::1]:9100"]]
        (is (= 200 (:status (app (with-host (mock/request :get "/") host)))) host)))
    (testing "refuses any other host with 403"
      (doseq [host ["evil.example" "evil.example:9100" "localhost.evil.example" ""]]
        (is (= 403 (:status (app (with-host (mock/request :get "/") host)))) host)))
    (testing "serves static files"
      (is (= 200 (:status (app (mock/request :get "/css/flow.css"))))))
    (testing "refuses a foreign host for static files too"
      (is (= 403 (:status (app (with-host (mock/request :get "/css/style.css")
                                 "evil.example"))))))))

(deftest test-post-requires-anti-forgery-token
  (let [app (console/handler)]
    (testing "rejects a post without a token"
      (is (= 403 (:status (app (mock/request :post "/"))))))))

(deftest test-routes-are-separate
  (let [console-app (console/handler)
        public-app  (server/app)]
    (testing "does not serve public app routes on the console"
      (doseq [path ["/up" "/app" "/api/maps" "/reset-password"]]
        (is (= 404 (:status (console-app (mock/request :get path)))) path)))
    (testing "does not serve the console on the public app"
      (is (not (str/includes? (str (:body (public-app (mock/request :get "/"))))
                              "Operator console"))))))

(deftest test-errors-are-plain-text
  (with-redefs [stats/fleet (fn [_] (throw (ex-info "<b>boom</b>" {})))]
    (let [response ((console/handler) (mock/request :get "/"))]
      (testing "answers a failure with 500 in plain text, not html"
        (is (= 500 (:status response)))
        (is (str/starts-with? (get-in response [:headers "Content-Type"]) "text/plain"))
        (is (string? (:body response)))))))

(deftest test-stats-page
  (create-test-user! {:email "jane@example.com" :display_name "Jane Example"})
  (let [body (:body ((console/handler) (mock/request :get "/")))]
    (testing "lists the users with their email"
      (is (str/includes? body "jane@example.com")))))

;;; Composer

(defn- browser
  "Returns a function that sends requests to `app` and keeps the console
   session cookie between them, like a browser."
  [app]
  (let [cookie (atom nil)]
    (fn [request]
      (let [response   (app (cond-> request @cookie (mock/header "cookie" @cookie)))
            set-cookie (get-in response [:headers "Set-Cookie"])]
        (when-let [c (some #(re-find #"parts-console=[^;]+" %)
                           (if (string? set-cookie) [set-cookie] set-cookie))]
          (reset! cookie c))
        response))))

(defn- csrf-token [body]
  (second (re-find #"name=\"__anti-forgery-token\"[^>]*value=\"([^\"]+)\"" body)))

(defn- json [response]
  (jsonista/read-value (:body response) jsonista/keyword-keys-object-mapper))

(defn- composer-session
  "Returns `[send form]`: a browser for the console and a function that
   builds the composer form with the anti-forgery token."
  []
  (let [send  (browser (console/handler))
        token (csrf-token (:body (send (mock/request :get "/"))))]
    [send (fn [fields]
            (merge {"__anti-forgery-token" token
                    "kind"                 "product-update"
                    "subject"              "News"
                    "body"                 "Hello **there**"}
                   fields))]))

(deftest test-composer-flow
  (with-redefs [conf/mail-reply-to        (constantly "op@example.com")
                operator-email/send-test! (fn [_] "op@example.com")]
    (let [[send form] (composer-session)
          preview     #(json (send (mock/request :post "/preview" (form %))))]
      (testing "the preview returns the html part of the draft"
        (let [result (preview {})]
          (is (str/includes? (:html result) "<strong>there</strong>"))
          (is (false? (:tested result)))))
      (testing "a test send records the draft as tested"
        (is (= 303 (:status (send (mock/request :post "/test" (form {}))))))
        (is (str/includes? (:body (send (mock/request :get "/"))) "Test sent to op@example.com"))
        (is (true? (:tested (preview {})))))
      (testing "an edit after the test marks the draft untested"
        (let [result (preview {"body" "Hello again"})]
          (is (false? (:tested result)))
          (is (str/includes? (:status result) "changed after the test"))))
      (testing "the page keeps the last previewed draft"
        (is (str/includes? (:body (send (mock/request :get "/"))) "Hello again"))))))

(deftest test-composer-errors
  (testing "a draft without a subject shows the problem and sends nothing"
    (let [sent (atom 0)]
      (with-redefs [conf/mail-reply-to        (constantly "op@example.com")
                    operator-email/send-test! (fn [_] (swap! sent inc))]
        (let [[send form] (composer-session)]
          (send (mock/request :post "/test" (form {"subject" ""})))
          (is (str/includes? (:body (send (mock/request :get "/"))) "Write a subject."))
          (is (zero? @sent))))))
  (testing "a failed send shows an alert, not an error page"
    (with-redefs [conf/mail-reply-to        (constantly "op@example.com")
                  operator-email/send-test! (fn [_] (throw (ex-info "Email send failed"
                                                                    {:type :smtp-error})))]
      (let [[send form] (composer-session)
            response    (send (mock/request :post "/test" (form {})))]
        (is (= 303 (:status response)))
        (is (str/includes? (:body (send (mock/request :get "/")))
                           "The test email was not sent. Email send failed"))))))

(deftest test-composer-line-breaks
  (with-redefs [conf/mail-reply-to        (constantly "op@example.com")
                operator-email/send-test! (fn [_] "op@example.com")]
    (let [[send form] (composer-session)]
      (testing "a test sent with crlf line breaks matches the same draft previewed with lf"
        (send (mock/request :post "/test" (form {"body" "One\r\nTwo"})))
        (is (true? (:tested (json (send (mock/request :post "/preview"
                                                      (form {"body" "One\nTwo"})))))))))))

(deftest test-composer-enter-does-not-submit
  (testing "the first submit button of the composer is disabled, so enter sends nothing"
    (let [body   (:body ((console/handler) (mock/request :get "/")))
          button (second (re-find #"<form[^>]*id=\"composer\"[\s\S]*?(<button[^>]*>)" body))]
      (is (re-find #"disabled" button)))))

(deftest test-composer-keeps-a-leading-newline
  (with-redefs [conf/mail-reply-to (constantly "op@example.com")]
    (let [[send form] (composer-session)]
      (send (mock/request :post "/preview" (form {"body" "\nStarts with a newline"})))
      (testing "the textarea adds the newline that the html parser drops"
        (is (str/includes? (:body (send (mock/request :get "/")))
                           ">\n\nStarts with a newline</textarea>"))))))

;;; Sending to Users

(defn- tested-session
  "Returns `[send form]` with a test sent of the default draft."
  []
  (let [[send form] (composer-session)]
    (send (mock/request :post "/test" (form {})))
    [send form]))

(deftest test-send-refusals
  (with-redefs [conf/mail-reply-to         (constantly "op@example.com")
                operator-email/send-test!  (fn [_] "op@example.com")
                operator-email/send-draft! (fn [_] (throw (ex-info "must not send" {})))
                conf/mail-sender-identity  (constantly "Example Ltd")]
    (testing "a draft that was not tested is refused"
      (let [[send form] (composer-session)]
        (send (mock/request :post "/send" (form {"ack" "on"})))
        (is (str/includes? (:body (send (mock/request :get "/"))) "Send a test of this exact draft first."))))
    (testing "an edit after the test is refused"
      (let [[send form] (tested-session)]
        (send (mock/request :post "/send" (form {"ack" "on" "body" "Changed"})))
        (is (str/includes? (:body (send (mock/request :get "/"))) "Send a test of this exact draft first."))))
    (testing "a send without the confirmation is refused"
      (let [[send form] (tested-session)]
        (send (mock/request :post "/send" (form {})))
        (is (str/includes? (:body (send (mock/request :get "/"))) "Confirm that you checked the test email."))))))

(deftest test-send-starts-and-clears-the-draft
  (let [started (atom nil)]
    (with-redefs [conf/mail-reply-to         (constantly "op@example.com")
                  operator-email/send-test!  (fn [_] "op@example.com")
                  operator-email/send-draft! (fn [draft] (reset! started draft) (random-uuid))
                  conf/mail-sender-identity  (constantly "Example Ltd")]
      (let [[send form] (tested-session)
            response    (send (mock/request :post "/send" (form {"ack" "on"})))]
        (testing "starts the send of the tested draft"
          (is (= 303 (:status response)))
          (is (= {:kind "product-update" :subject "News" :body "Hello **there**"} @started)))
        (testing "clears the composer"
          (is (str/includes? (:body (send (mock/request :get "/"))) "name=\"subject\" type=\"text\" value=\"\"")))))))

(deftest test-send-while-another-sends
  (with-redefs [conf/mail-reply-to         (constantly "op@example.com")
                operator-email/send-test!  (fn [_] "op@example.com")
                operator-email/send-draft! (constantly nil)
                conf/mail-sender-identity  (constantly "Example Ltd")]
    (let [[send form] (tested-session)]
      (send (mock/request :post "/send" (form {"ack" "on"})))
      (testing "shows that another email is sending and keeps the draft"
        (let [body (:body (send (mock/request :get "/")))]
          (is (str/includes? body "Another email is still sending."))
          (is (str/includes? body "value=\"News\"")))))))

(deftest test-current-send
  (testing "reports idle when no email sends"
    (with-redefs [operator-email/current-send (constantly nil)]
      (is (= {:state "idle"} (json ((console/handler) (mock/request :get "/sends/current")))))))
  (testing "reports the progress of the email that sends"
    (let [id (random-uuid)]
      (with-redefs [operator-email/current-send
                    (constantly {:id id :subject "News" :sent 3 :failed 1 :total 9 :state :sending})]
        (is (= {:id (str id) :subject "News" :sent 3 :failed 1 :total 9 :state "sending"}
               (json ((console/handler) (mock/request :get "/sends/current")))))))))

(defn- store-email! [subject completed?]
  (:id (db/insert! :operator_emails (cond-> {:kind "service-notice" :subject subject :body "B"}
                                      completed? (assoc :completed_at [:now])))))

(deftest test-sent-list
  (let [ok  (create-test-user! {:email "ok@example.com"})
        bad (create-test-user! {:email "bad@example.com"})
        id  (store-email! "Terms update" true)]
    (db/insert! :operator_email_deliveries {:email_id id :user_id (:id ok) :sent_at [:now]})
    (db/insert! :operator_email_deliveries {:email_id id :user_id (:id bad) :error "550 Mailbox unavailable"})
    (let [body (:body ((console/handler) (mock/request :get "/")))]
      (testing "lists the email with its failed addresses and a retry"
        (is (str/includes? body "Terms update"))
        (is (str/includes? body "bad@example.com"))
        (is (str/includes? body "Retry failed")))
      (testing "shows the result in the banner after the send"
        (is (str/includes? body "<b>Sent</b> to 1 of 2 · 1 failed")))))
  (testing "an email that stopped before the end offers to resume"
    (store-email! "Stopped one" false)
    (let [body (:body ((console/handler) (mock/request :get "/")))]
      (is (str/includes? body "Resume"))
      (is (str/includes? body "<b>Stopped</b>")))))

(deftest test-banner-follows-the-sending-email
  (let [older (random-uuid)
        email (fn [id state subject]
                {:id         id                      :kind         "service-notice" :subject subject :state state
                 :created_at (java.sql.Timestamp. 0) :completed_at nil
                 :sent       1                       :failed       0                :waiting 1       :total 2     :failures []})]
    (with-redefs [operator-email/sent-emails
                  (constantly [(email (random-uuid) :stopped "Newer") (email older :sending "Older")])]
      (let [body (:body ((console/handler) (mock/request :get "/")))]
        (testing "the banner shows the resumed older email and asks for polling"
          (is (str/includes? body (str "data-sending=\"" older "\"")))
          (is (str/includes? body "<b>Sending</b> “Older”")))))))

(deftest test-send-requires-the-company-line
  (with-redefs [conf/mail-reply-to         (constantly "op@example.com")
                operator-email/send-test!  (fn [_] "op@example.com")
                operator-email/send-draft! (fn [_] (throw (ex-info "must not send" {})))
                conf/mail-sender-identity  (constantly nil)]
    (let [[send form] (tested-session)]
      (send (mock/request :post "/send" (form {"ack" "on"})))
      (testing "refuses to send to users without the company line"
        (is (str/includes? (:body (send (mock/request :get "/")))
                           "Set PARTS__MAIL__SENDER_IDENTITY, the company line, before sending to Users."))))))

(deftest test-subject-is-one-line
  (let [tested (atom nil)]
    (with-redefs [conf/mail-reply-to        (constantly "op@example.com")
                  operator-email/send-test! (fn [draft] (reset! tested draft) "op@example.com")]
      (let [[send form] (composer-session)]
        (send (mock/request :post "/test" (form {"subject" "News\r\nBcc: x@example.com"})))
        (testing "turns line breaks in the subject into spaces"
          (is (= "News Bcc: x@example.com" (:subject @tested))))))))
