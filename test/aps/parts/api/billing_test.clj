(ns aps.parts.api.billing-test
  (:require
   [aps.parts.api.billing :as billing-api]
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [aps.parts.helpers.utils :refer [create-test-user! stripe-sig-header
                                    stripe-test-config with-test-db]]
   [aps.parts.server :as server]
   [aps.parts.stripe :as stripe]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [jsonista.core :as json]
   [ring.mock.request :as mock])
  (:import
   (java.time Instant LocalDate ZoneOffset)))

(use-fixtures :once with-test-db)

(def ^:private stripe-config stripe-test-config)

(def ^:private period-end 1790000000)
(def ^:private period-end-date
  (LocalDate/ofInstant (Instant/ofEpochSecond period-end) ZoneOffset/UTC))

(defn- subscription-fetch
  "A stub for `stripe/get-subscription!` returning a dahlia-shaped
   subscription: period end lives per-item, not at the top level."
  ([] (subscription-fetch "active" period-end))
  ([status end]
   (fn [_cfg _sub-id]
     {:id     "sub_test"
      :status status
      :items  {:data [{:current_period_end end}]}})))

(defn- webhook-request
  "A signed webhook POST, built with ring-mock so the body and header
   shapes match what the real route hands the handler."
  ([payload]
   (webhook-request payload (stripe-sig-header (:webhook-secret stripe-config) payload)))
  ([payload sig-header]
   (-> (mock/request :post "/stripe/webhook")
       (mock/body payload)
       (mock/header "stripe-signature" sig-header))))

(defn- event-json [event-type object]
  (json/write-value-as-string
   {:id "evt_test_1" :type event-type :data {:object object}}))

(defn- checkout-json
  "A checkout.session.completed payload for `user`, as our sessions look:
   client_reference_id + metadata plan."
  [user & {:keys [customer plan] :or {customer "cus_test" plan "monthly"}}]
  (event-json "checkout.session.completed"
              {:id                  "cs_1"
               :customer            customer
               :subscription        "sub_test"
               :client_reference_id (some-> user :id str)
               :metadata            (if plan {:plan plan} {})}))

(defn- billing-row [email]
  (db/query-one
   (db/sql-format {:select [:paid_through_date :stripe_customer_id
                            :stripe_subscription_status]
                   :from   [:users]
                   :where  [:= :email email]})))

(defn- paid-through [email]
  (some-> (:paid_through_date (billing-row email)) .toLocalDate))

(defn- post-webhook
  ([request] (post-webhook request (subscription-fetch)))
  ([request sub-fetch]
   (with-redefs [conf/stripe-config       (constantly stripe-config)
                 stripe/get-subscription! sub-fetch]
     (billing-api/webhook request))))

(deftest webhook-rejects-unverified-requests-test
  (testing "responds 404 when self-serve billing is not configured"
    (with-redefs [conf/stripe-config (constantly nil)]
      (is (= 404 (:status (billing-api/webhook (webhook-request "{}")))))))

  (testing "responds 400 to a bad signature, touching nothing"
    (let [user    (create-test-user! {:email "victim@example.com"})
          payload (checkout-json user :customer "cus_evil")]
      (is (= 400 (:status (post-webhook (webhook-request payload "t=1,v1=deadbeef")))))
      (is (nil? (:stripe_customer_id (billing-row "victim@example.com"))))
      (is (nil? (paid-through "victim@example.com")))))

  (testing "responds 400 to a missing signature header"
    (is (= 400 (:status (post-webhook (-> (mock/request :post "/stripe/webhook")
                                          (mock/body "{}"))))))))

(deftest webhook-through-assembled-app-test
  (testing "a signed payload survives the assembled middleware stack — the
            tripwire for the raw-body invariant: no body-parsing middleware
            may run before signature verification"
    (with-redefs [conf/stripe-config (constantly stripe-config)]
      (let [app (server/app)]
        (is (= 200 (:status (app (webhook-request
                                  (event-json "unhandled.event" {:id "obj_1"}))))))))))

(deftest webhook-checkout-completed-test
  (testing "links customer, records status, extends to the real period end"
    (let [user     (create-test-user! {:email "buyer@example.com"})
          response (post-webhook (webhook-request (checkout-json user :customer "cus_buyer")))]
      (is (= 200 (:status response)))
      (let [row (billing-row "buyer@example.com")]
        (is (= "cus_buyer" (:stripe_customer_id row)))
        (is (= "active" (:stripe_subscription_status row)))
        (is (= period-end-date (paid-through "buyer@example.com"))))))

  (testing "an account already paid further ahead (concierge) keeps its later date"
    (let [user   (create-test-user! {:email "concierge@example.com"})
          beyond (.plusYears period-end-date 1)]
      (db/update! :users {:paid_through_date beyond} [:= :id (:id user)])
      (post-webhook (webhook-request (checkout-json user :customer "cus_concierge")))
      (is (= beyond (paid-through "concierge@example.com")))
      (is (= "cus_concierge" (:stripe_customer_id (billing-row "concierge@example.com"))))))

  (testing "an account linked to a different customer keeps its link (no orphaned
            renewals) but still receives the paid time"
    (let [user (create-test-user! {:email "double@example.com"})]
      (db/update! :users {:stripe_customer_id "cus_first"} [:= :id (:id user)])
      (is (= 200 (:status (post-webhook
                           (webhook-request (checkout-json user :customer "cus_second"))))))
      (is (= "cus_first" (:stripe_customer_id (billing-row "double@example.com"))))
      (is (= period-end-date (paid-through "double@example.com")))))

  (testing "a Stripe fetch failure returns 500 so Stripe redelivers"
    (let [user (create-test-user! {:email "flaky@example.com"})]
      (is (= 500 (:status (post-webhook
                           (webhook-request (checkout-json user))
                           (fn [_ _] (throw (ex-info "boom" {:type :stripe-api})))))))
      (is (nil? (paid-through "flaky@example.com")))))

  (testing "ignores a session without a recognised plan (not a self-serve checkout)"
    (let [user    (create-test-user! {:email "other-checkout@example.com"})
          payload (checkout-json user :customer "cus_other" :plan nil)]
      (is (= 200 (:status (post-webhook (webhook-request payload)))))
      (is (nil? (:stripe_customer_id (billing-row "other-checkout@example.com"))))
      (is (nil? (paid-through "other-checkout@example.com")))))

  (testing "ignores an unknown or malformed client reference"
    (doseq [reference [(str (random-uuid)) "not-a-uuid" nil]]
      (let [payload (event-json "checkout.session.completed"
                                {:id                  "cs_5"
                                 :customer            "cus_ghost"
                                 :subscription        "sub_test"
                                 :client_reference_id reference
                                 :metadata            {:plan "monthly"}})]
        (is (= 200 (:status (post-webhook (webhook-request payload)))))))))

(deftest webhook-invoice-paid-test
  (testing "a renewal invoice moves paid-through to the paid period's end"
    (let [user    (create-test-user! {:email "renewal@example.com"})
          _       (db/update! :users {:stripe_customer_id "cus_renewal"} [:= :id (:id user)])
          payload (event-json "invoice.paid"
                              {:id       "in_1"
                               :customer "cus_renewal"
                               :lines    {:data [{:period {:end period-end}}]}})]
      (is (= 200 (:status (post-webhook (webhook-request payload)))))
      (is (= period-end-date (paid-through "renewal@example.com")))))

  (testing "a plan-switch invoice takes the latest line period, not the first
            (proration lines sort first and end at the switch date)"
    (let [user       (create-test-user! {:email "switcher@example.com"})
          switch-end 1760000000
          payload    (event-json "invoice.paid"
                                 {:id       "in_2"
                                  :customer "cus_switcher"
                                  :lines    {:data [{:period {:end switch-end}}
                                                    {:period {:end period-end}}]}})]
      (db/update! :users {:stripe_customer_id "cus_switcher"} [:= :id (:id user)])
      (post-webhook (webhook-request payload))
      (is (= period-end-date (paid-through "switcher@example.com")))))

  (testing "never moves paid-through backwards"
    (let [user   (create-test-user! {:email "ahead@example.com"})
          beyond (.plusYears period-end-date 1)]
      (db/update! :users {:stripe_customer_id "cus_ahead"
                          :paid_through_date  beyond}
                  [:= :id (:id user)])
      (post-webhook (webhook-request
                     (event-json "invoice.paid"
                                 {:id       "in_3"
                                  :customer "cus_ahead"
                                  :lines    {:data [{:period {:end period-end}}]}})))
      (is (= beyond (paid-through "ahead@example.com")))))

  (testing "replaying the same event is harmless (idempotent)"
    (let [user    (create-test-user! {:email "replay@example.com"})
          _       (db/update! :users {:stripe_customer_id "cus_replay"} [:= :id (:id user)])
          payload (event-json "invoice.paid"
                              {:id       "in_4"
                               :customer "cus_replay"
                               :lines    {:data [{:period {:end period-end}}]}})]
      (post-webhook (webhook-request payload))
      (post-webhook (webhook-request payload))
      (is (= period-end-date (paid-through "replay@example.com")))))

  (testing "an invoice for an unlinked customer is acknowledged and ignored
            (concierge invoices flow through the same account)"
    (let [payload (event-json "invoice.paid"
                              {:id       "in_5"
                               :customer "cus_concierge_manual"
                               :lines    {:data [{:period {:end period-end}}]}})]
      (is (= 200 (:status (post-webhook (webhook-request payload)))))))

  (testing "a malformed invoice payload is acknowledged and ignored"
    (let [payload (event-json "invoice.paid" {:id "in_6" :customer "cus_renewal"})]
      (is (= 200 (:status (post-webhook (webhook-request payload))))))))

(deftest webhook-subscription-lifecycle-test
  (testing "subscription updates keep the status current"
    (let [user (create-test-user! {:email "lifecycle@example.com"})]
      (db/update! :users {:stripe_customer_id "cus_lifecycle"} [:= :id (:id user)])
      (post-webhook (webhook-request
                     (event-json "customer.subscription.updated"
                                 {:id "sub_1" :customer "cus_lifecycle" :status "past_due"})))
      (is (= "past_due" (:stripe_subscription_status (billing-row "lifecycle@example.com"))))))

  (testing "cancellation records canceled status but keeps the paid window"
    (let [user (create-test-user! {:email "cancelled@example.com"})]
      (db/update! :users {:stripe_customer_id         "cus_cancelled"
                          :stripe_subscription_status "active"
                          :paid_through_date          period-end-date}
                  [:= :id (:id user)])
      (is (= 200 (:status (post-webhook
                           (webhook-request
                            (event-json "customer.subscription.deleted"
                                        {:id       "sub_2"
                                         :customer "cus_cancelled"
                                         :status   "canceled"}))))))
      (let [row (billing-row "cancelled@example.com")]
        (is (= "canceled" (:stripe_subscription_status row)))
        (is (= period-end-date (paid-through "cancelled@example.com"))))))

  (testing "a subscription event for an unlinked customer is acknowledged and ignored"
    (is (= 200 (:status (post-webhook
                         (webhook-request
                          (event-json "customer.subscription.deleted"
                                      {:id "sub_3" :customer "cus_ghost" :status "canceled"}))))))))

(deftest create-checkout-session-test
  (testing "creates a session for the chosen plan and returns its URL"
    (let [user     (create-test-user! {:email "buyer2@example.com"})
          captured (atom nil)]
      (with-redefs [conf/stripe-config              (constantly stripe-config)
                    stripe/create-checkout-session! (fn [_cfg params]
                                                      (reset! captured params)
                                                      {:url "https://checkout.stripe.com/c/pay/cs_test"})]
        (let [response (billing-api/create-checkout-session
                        {:identity    {:sub (str (:id user))}
                         :body-params {:plan "yearly"}})]
          (is (= 200 (:status response)))
          (is (= {:url "https://checkout.stripe.com/c/pay/cs_test"} (:body response)))
          (is (= "price_yearly" (get-in @captured [:line_items 0 :price])))
          (is (= "buyer2@example.com" (:customer_email @captured)))))))

  (testing "an already-linked account reuses its Stripe customer"
    (let [user     (create-test-user! {:email "linked-buyer@example.com"})
          _        (db/update! :users {:stripe_customer_id "cus_linked"} [:= :id (:id user)])
          captured (atom nil)]
      (with-redefs [conf/stripe-config              (constantly stripe-config)
                    stripe/create-checkout-session! (fn [_cfg params]
                                                      (reset! captured params)
                                                      {:url "https://checkout.stripe.com/x"})]
        (billing-api/create-checkout-session
         {:identity    {:sub (str (:id user))}
          :body-params {:plan "monthly"}})
        (is (= "cus_linked" (:customer @captured)))
        (is (not (contains? @captured :customer_email))))))

  (testing "refuses while a live subscription exists (no double-subscribe)"
    (let [user (create-test-user! {:email "subscribed@example.com"})]
      (db/update! :users {:stripe_customer_id         "cus_subscribed"
                          :stripe_subscription_status "active"}
                  [:= :id (:id user)])
      (with-redefs [conf/stripe-config (constantly stripe-config)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"active subscription"
                              (billing-api/create-checkout-session
                               {:identity    {:sub (str (:id user))}
                                :body-params {:plan "monthly"}}))))))

  (testing "a cancelled subscriber can subscribe again"
    (let [user     (create-test-user! {:email "resubscriber@example.com"})
          captured (atom nil)]
      (db/update! :users {:stripe_customer_id         "cus_resub"
                          :stripe_subscription_status "canceled"}
                  [:= :id (:id user)])
      (with-redefs [conf/stripe-config              (constantly stripe-config)
                    stripe/create-checkout-session! (fn [_cfg params]
                                                      (reset! captured params)
                                                      {:url "https://checkout.stripe.com/y"})]
        (is (= 200 (:status (billing-api/create-checkout-session
                             {:identity    {:sub (str (:id user))}
                              :body-params {:plan "yearly"}}))))
        (is (= "cus_resub" (:customer @captured))))))

  (testing "rejects an unknown plan"
    (let [user (create-test-user! {:email "confused@example.com"})]
      (with-redefs [conf/stripe-config (constantly stripe-config)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plan"
                              (billing-api/create-checkout-session
                               {:identity    {:sub (str (:id user))}
                                :body-params {:plan "weekly"}}))))))

  (testing "404s when self-serve billing is not configured"
    (let [user (create-test-user! {:email "early@example.com"})]
      (with-redefs [conf/stripe-config (constantly nil)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not enabled"
                              (billing-api/create-checkout-session
                               {:identity    {:sub (str (:id user))}
                                :body-params {:plan "monthly"}})))))))

(deftest create-portal-session-test
  (testing "returns the portal URL for a linked account"
    (let [user (create-test-user! {:email "manager@example.com"})]
      (db/update! :users {:stripe_customer_id "cus_manager"} [:= :id (:id user)])
      (with-redefs [conf/stripe-config            (constantly stripe-config)
                    stripe/create-portal-session! (fn [_cfg params]
                                                    (is (= "cus_manager" (:customer params)))
                                                    {:url "https://billing.stripe.com/p/session_test"})]
        (let [response (billing-api/create-portal-session
                        {:identity {:sub (str (:id user))}})]
          (is (= 200 (:status response)))
          (is (= {:url "https://billing.stripe.com/p/session_test"} (:body response)))))))

  (testing "rejects an account with no Stripe customer to manage"
    (let [user (create-test-user! {:email "unlinked@example.com"})]
      (with-redefs [conf/stripe-config (constantly stripe-config)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"[Nn]o subscription"
                              (billing-api/create-portal-session
                               {:identity {:sub (str (:id user))}})))))))
