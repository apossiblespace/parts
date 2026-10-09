(ns aps.parts.operator-email
  "Builds and sends the emails that the operator writes to Users: Product
   updates and Service notices. See ADR-0020 and `CONTEXT.md`.

   The Operator console calls this namespace. It calls `aps.parts.mail` to
   send, and `aps.parts.email-layout` for the layout. One function,
   `message`, builds the email for the preview, the test send and the real
   send, so the preview always shows what is sent.

   A draft is a map of `:kind`, `:subject` and `:body`. The kind is
   \"product-update\" or \"service-notice\", and the body is Markdown. A
   footer that the operator cannot edit ends the email.

   A sent email is a row of `operator_emails`, and each recipient gets a
   row of `operator_email_deliveries` after the relay accepts the message.
   One email sends at a time, in the background. A send that stops, for
   example at a restart, can resume, and a resumed send skips every User
   who already has a sent row."
  (:require
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [aps.parts.db.erasure :as erasure]
   [aps.parts.email-layout :as layout]
   [aps.parts.mail :as mail]
   [clojure.string :as str]
   [com.brunobonacci.mulog :as mulog])
  (:import
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)
   (java.util HexFormat)))

(def kinds #{"product-update" "service-notice"})

(defn validate
  "Returns a message that says what is wrong with `draft`, or nil when the
   draft can be sent."
  [{:keys [subject body]}]
  (cond
    (str/blank? subject) "Write a subject."
    (str/blank? body)    "Write the email."))

(defn draft-hash
  "Returns a hex SHA-256 of the kind, subject and body of `draft`. Two
   drafts have the same hash only when all three are equal."
  [{:keys [kind subject body]}]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (str kind "\u0000" subject "\u0000" body)
                                  StandardCharsets/UTF_8))))

;;; Content

(defn- product-update? [kind] (= kind "product-update"))

;; The footer names the recipient, links the unsubscribe page and the
;; Privacy Policy, and identifies the company. UK company law requires the
;; company line in business emails. See ADR-0020.
(defn- footer
  "Returns the footer lines of an email of `kind` to `to`."
  [kind {:keys [to unsubscribe-url]}]
  (remove nil?
          [(str (if (product-update? kind)
                  "You get product updates because you have a Parts account"
                  "This is a service notice about your Parts account")
                " (sent to " to ").")
           (cond-> []
             (product-update? kind) (conj ["Unsubscribe" unsubscribe-url])
             :always                (conj (layout/privacy-link)))
           (conf/mail-sender-identity)]))

(defn content
  "Returns the `{:text :html}` parts of the email for `draft` to the
   recipient `to`. A Product update links `unsubscribe-url` in its footer."
  [{:keys [kind body]} recipient]
  (layout/content body (footer kind recipient)))

(defn message
  "Returns the postal message map of `draft` for the recipient `to`. A
   Product update carries the one-click unsubscribe headers of RFC 8058."
  [{:keys [kind subject] :as draft} {:keys [to unsubscribe-url] :as recipient}]
  (cond-> {:to      to
           :subject subject
           :body    (layout/alternative (content draft recipient))}
    (product-update? kind)
    (assoc "List-Unsubscribe"      (str "<" unsubscribe-url ">")
           "List-Unsubscribe-Post" "List-Unsubscribe=One-Click")))

;;; Test send

(defn- unsubscribe-url
  "Returns the unsubscribe link for the unsubscribe token `token`."
  [token]
  (str (conf/base-url) "/unsubscribe/" token))

(defn test-unsubscribe-url
  "Returns the unsubscribe link of a test email. Its token is malformed, so
   the link opens the neutral 'not valid' page and unsubscribes nobody."
  []
  (unsubscribe-url "test"))

(defn preview
  "Returns the `{:text :html}` parts of `draft` for the console preview,
   with a placeholder recipient and the test unsubscribe link."
  [draft]
  (content draft {:to "name@example.com" :unsubscribe-url (test-unsubscribe-url)}))

(defn send-test!
  "Sends `draft` to the operator with a \"[Test] \" subject prefix. Returns
   the address. Throws `:config-error` when no operator address is
   configured, and `:smtp-error` when the relay refuses the email."
  [draft]
  (let [to (or (conf/mail-reply-to)
               (throw (ex-info "No operator address is configured (PARTS__MAIL__REPLY_TO)"
                               {:type :config-error})))]
    (mail/send-personal!
     (update (message draft {:to to :unsubscribe-url (test-unsubscribe-url)})
             :subject #(str "[Test] " %)))
    to))

;;; Sending to Users

(defn- count-where [where]
  [:filter [:count :*] {:where where}])

(defn- audience-where
  [kind]
  (cond-> [:and
           (erasure/exclude-tombstone :users.id)
           [:= :users.deletion_requested_at nil]]
    (product-update? kind) (conj [:= :users.product_updates_opted_out_at nil])))

;; The recipient count uses `audience-where`, the condition of the send,
;; so the count in the page is the number of Users who get the email.
(defn audience
  "Returns how many Users a new email of `kind` goes to, as
   `{:users :pending :opted-out :recipients}`. Opted-out Users count only
   for a Product update."
  [kind]
  (let [{:keys [users pending opted_out recipients]}
        (db/query-one
         (db/sql-format
          {:select [[[:count :*] :users]
                    [(count-where [:<> :users.deletion_requested_at nil]) :pending]
                    [(count-where [:and [:= :users.deletion_requested_at nil]
                                   [:<> :users.product_updates_opted_out_at nil]])
                     :opted_out]
                    [(count-where (audience-where kind)) :recipients]]
           :from   [:users]
           :where  (erasure/exclude-tombstone :users.id)}))]
    {:users      users
     :pending    pending
     :opted-out  (if (product-update? kind) opted_out 0)
     :recipients recipients}))

(defn- delivered
  "A condition that is true when the User has a delivery row for the email
   `id` that matches `where`."
  [id where]
  [:exists {:select [1]
            :from   [:operator_email_deliveries]
            :where  [:and
                     [:= :operator_email_deliveries.email_id id]
                     [:= :operator_email_deliveries.user_id :users.id]
                     where]}])

(defn- recipients
  "Returns the Users in the audience of `email` who have no sent delivery,
   with the Users whose delivery failed last. With `only-failed?`, returns
   only those whose delivery failed."
  [{:keys [id kind]} only-failed?]
  (let [failed (delivered id [:<> :operator_email_deliveries.error nil])]
    (db/query
     (db/sql-format
      {:select   [:users.id :users.email :users.unsubscribe_token]
       :from     [:users]
       :where    (cond-> (conj (audience-where kind)
                               [:not (delivered id [:<> :operator_email_deliveries.sent_at nil])])
                   only-failed? (conj failed))
       :order-by [[failed :asc] [:users.created_at :asc]]}))))

(defn- still-in-audience?
  "Returns true when the User `user-id` is still in the audience of an
   email of `kind`. A User can leave it during a send by opting out or by
   asking for deletion."
  [kind user-id]
  (some? (db/query-one
          (db/sql-format
           {:select [1]
            :from   [:users]
            :where  (conj (audience-where kind) [:= :users.id user-id])}))))

(defn- waiting-count
  "Returns how many Users in the audience of `email` have no delivery row."
  [{:keys [id kind]}]
  (:c (db/query-one
       (db/sql-format
        {:select [[[:count :*] :c]]
         :from   [:users]
         :where  (conj (audience-where kind) [:not (delivered id true)])}))))

(defn- record-delivery!
  "Records the delivery of the email `email-id` to `user-id`. A nil `error`
   means that the relay accepted the message. A retry replaces the row."
  [email-id user-id error]
  (db/query
   (db/sql-format
    {:insert-into   :operator_email_deliveries
     :values        [{:email_id email-id
                      :user_id  user-id
                      :sent_at  (when-not error [:now])
                      :error    error}]
     :on-conflict   [:email_id :user_id]
     :do-update-set [:sent_at :error]})))

(defn- email-row [id]
  (db/query-one (db/sql-format {:select [:*] :from [:operator_emails] :where [:= :id id]})))

(def ^:private max-failures-in-a-row
  "Failures in a row after which a send stops, because the relay, not the
   addresses, is then the likely cause."
  5)

(defn- deliver!
  "Sends `email` to `user` and records the delivery. Returns the error, or
   nil when the relay accepted the message. Rethrows a `:config-error`,
   because no other User can get the email either."
  [email user]
  (let [error (try
                (mail/send-personal!
                 (message email {:to              (:email user)
                                 :unsubscribe-url (unsubscribe-url (:unsubscribe_token user))}))
                nil
                (catch Exception e
                  (when (= :config-error (:type (ex-data e)))
                    (throw e))
                  (or (get-in (ex-data e) [:result :message])
                      (ex-message e)
                      (.getName (class e)))))]
    (when error
      (mulog/log ::delivery-failed :email-id (:id email) :user-id (:id user) :error error))
    (record-delivery! (:id email) (:id user) error)
    error))

;; The row is written after the relay accepts the message. A crash between
;; the two can send one email twice, but it never skips a User.
(defn send-email!
  "Sends the email `id` to every User who must still get it, one at a
   time, and records each delivery. With `only-failed?`, sends only to the
   Users whose delivery failed. Sets `completed_at` at the end. Does
   nothing when there is no email `id`.

   Throws, and leaves the email stopped, on a `:config-error`, or after
   `max-failures-in-a-row` failures in a row when not `only-failed?`."
  ([id] (send-email! id false))
  ([id only-failed?]
   (when-let [email (email-row id)]
     (reduce (fn [streak user]
               (cond
                 (not (still-in-audience? (:kind email) (:id user))) streak
                 (nil? (deliver! email user))                       0
                 ;; A retry of failures expects failures, so it does not stop.
                 (or only-failed? (< (inc streak) max-failures-in-a-row)) (inc streak)
                 :else (throw (ex-info "Too many deliveries failed in a row"
                                       {:type :relay-failing :email-id id}))))
             0
             (recipients email only-failed?))
     ;; The first finish is kept, so a retry does not change when the
     ;; email was first sent to its audience.
     (db/update! :operator_emails
                 {:completed_at [:coalesce :completed_at [:now]]}
                 [:= :id id]))))

;; The id of the email that is sending, or ::starting while a new email is
;; stored. It is nil when no email is sending.
(defonce ^:private running (atom nil))

(defn- start!
  [id only-failed?]
  (future
    (try
      (send-email! id only-failed?)
      (catch Throwable e
        (mulog/log ::send-stopped :email-id id :error (ex-message e)))
      (finally
        (reset! running nil)))))

(defn send-draft!
  "Stores `draft` as an email and starts to send it in the background.
   Returns the email id, or nil when another email is sending."
  [draft]
  (when (compare-and-set! running nil ::starting)
    (try
      (let [id (:id (db/insert! :operator_emails (select-keys draft [:kind :subject :body])))]
        (reset! running id)
        (start! id false)
        id)
      (catch Exception e
        (reset! running nil)
        (throw e)))))

;; A finished email retries only its failed deliveries, so a User who
;; signed up later does not get an old email. A stopped email goes to its
;; whole audience as it is now.
(defn resume!
  "Starts to send the email `id` again. A stopped email goes to the Users
   in its audience who did not get it. A finished email goes to the Users
   whose delivery failed. Returns `id`, or nil when there is no such email
   or another email is sending."
  [id]
  (when-let [{:keys [completed_at]} (email-row id)]
    (when (compare-and-set! running nil id)
      (start! id (some? completed_at))
      id)))

(defn- email-rows
  []
  (db/query
   (db/sql-format
    {:select    [:e.id :e.kind :e.subject :e.created_at :e.completed_at
                 [[:count :d.sent_at] :sent]
                 [(count-where [:and [:<> :d.error nil] [:= :d.sent_at nil]]) :failed]]
     :from      [[:operator_emails :e]]
     :left-join [[:operator_email_deliveries :d] [:= :d.email_id :e.id]]
     :group-by  [:e.id]
     :order-by  [[:e.created_at :desc]]})))

(defn- summarize
  "Adds `:state` (`:sending`, `:done` or `:stopped`), `:waiting` and
   `:total` to an email row. An email that finished once, also during a
   retry, has no waiting Users, so a User who signed up later does not
   change its total."
  [{:keys [id completed_at sent failed] :as row}]
  (let [state   (cond (= @running id) :sending
                      completed_at    :done
                      :else           :stopped)
        waiting (if completed_at 0 (waiting-count row))]
    (assoc row :state state :waiting waiting :total (+ sent failed waiting))))

(defn sent-emails
  "Returns the summary of every email, newest first. Each has `:failures`,
   the `{:email :error}` of each User whose delivery failed, and
   `:retryable`, how many of those Users are still in the audience."
  []
  (let [failures (group-by :email_id
                           (db/query
                            (db/sql-format
                             {:select [:d.email_id :u.email :d.error]
                              :from   [[:operator_email_deliveries :d]]
                              :join   [[:users :u] [:= :u.id :d.user_id]]
                              :where  [:and [:<> :d.error nil] [:= :d.sent_at nil]]})))]
    (for [row (email-rows)]
      (assoc (summarize row)
             :failures  (get failures (:id row) [])
             :retryable (if (pos? (:failed row)) (count (recipients row true)) 0)))))

(defn current-send
  "Returns the summary of the email that is sending, or nil."
  []
  (when (uuid? @running)
    (first (filter #(= :sending (:state %)) (sent-emails)))))
