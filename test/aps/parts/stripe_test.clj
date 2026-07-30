(ns aps.parts.stripe-test
  (:require
   [aps.parts.stripe :as stripe]
   [clojure.string :as cstr]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.net URLDecoder)
   (javax.crypto Mac)
   (javax.crypto.spec SecretKeySpec)))

(defn- hex-hmac
  "Independent HMAC-SHA256 so these tests lock the signing algorithm down,
   rather than mirroring whatever the implementation happens to compute."
  [^String secret ^String payload]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256")))]
    (apply str (map #(format "%02x" %) (.doFinal mac (.getBytes payload "UTF-8"))))))

(defn- sig-header
  "A well-formed Stripe-Signature header for `payload` signed at `timestamp`."
  [secret timestamp payload]
  (str "t=" timestamp ",v1=" (hex-hmac secret (str timestamp "." payload))))

(defn- decoded-pairs
  "Parse a form-encoded string into a set of [key value] tuples, decoded."
  [encoded]
  (into #{}
        (map (fn [pair]
               (mapv #(URLDecoder/decode ^String % "UTF-8")
                     (cstr/split pair #"=" 2))))
        (cstr/split encoded #"&")))

(deftest form-encode-test
  (testing "flat params become key=value pairs"
    (is (= #{["mode" "subscription"] ["customer" "cus_123"]}
           (decoded-pairs (stripe/form-encode {:mode "subscription" :customer "cus_123"})))))

  (testing "nested maps use Stripe's bracket syntax"
    (is (= #{["metadata[plan]" "monthly"]}
           (decoded-pairs (stripe/form-encode {:metadata {:plan "monthly"}})))))

  (testing "vectors are indexed, and their maps nest further"
    (is (= #{["line_items[0][price]" "price_123"]
             ["line_items[0][quantity]" "1"]}
           (decoded-pairs (stripe/form-encode {:line_items [{:price "price_123" :quantity 1}]})))))

  (testing "values with reserved characters survive a round-trip"
    (is (= #{["success_url" "https://parts.test/app/account?checkout=success"]}
           (decoded-pairs (stripe/form-encode {:success_url "https://parts.test/app/account?checkout=success"}))))))

(deftest checkout-session-params-test
  (let [params (stripe/checkout-session-params
                {:user-id  #uuid "00000000-0000-0000-0000-000000000001"
                 :email    "therapist@example.com"
                 :plan     :monthly
                 :price-id "price_123"
                 :base-url "https://parts.test"})]
    (testing "builds a subscription-mode session for the chosen price"
      (is (= "subscription" (:mode params)))
      (is (= [{:price "price_123" :quantity 1}] (:line_items params)))
      (is (= "monthly" (get-in params [:metadata :plan])))
      (is (= "00000000-0000-0000-0000-000000000001" (:client_reference_id params)))
      (is (= "therapist@example.com" (:customer_email params))))

    (testing "success and cancel URLs land on the SPA account page"
      (is (= "https://parts.test/app/account?checkout=success" (:success_url params)))
      (is (= "https://parts.test/app/account" (:cancel_url params))))

    ;; TASK-046 / task-009 AC#9: "no client data reaches Stripe" is a code
    ;; invariant. The payload may carry the therapist's user id, email and
    ;; chosen plan — never a client name, Map title, or clinical content.
    ;; A new key showing up here must be a deliberate, reviewed decision.
    (testing "payload keys are a closed allowlist (no clinical data can reach Stripe)"
      (is (= #{:mode :line_items :client_reference_id :customer_email
               :metadata :automatic_tax :integration_identifier
               :success_url :cancel_url}
             (set (keys params))))
      (is (= #{:plan} (set (keys (:metadata params))))))

    (testing "automatic tax is enabled (registration lives in Stripe Tax settings)"
      (is (= {:enabled true} (:automatic_tax params))))

    ;; Deliberately no payment_method_types: Stripe then offers whatever
    ;; payment methods the Dashboard enables (dynamic payment methods).
    (testing "payment_method_types is never sent"
      (is (not (contains? params :payment_method_types))))))

(deftest checkout-session-params-existing-customer-test
  (testing "an already-linked customer is reused instead of a raw email"
    (let [params (stripe/checkout-session-params
                  {:user-id  #uuid "00000000-0000-0000-0000-000000000001"
                   :email    "therapist@example.com"
                   :customer "cus_123"
                   :plan     :yearly
                   :price-id "price_456"
                   :base-url "https://parts.test"})]
      (is (= "cus_123" (:customer params)))
      (is (not (contains? params :customer_email))))))

(deftest portal-session-params-test
  (let [params (stripe/portal-session-params
                {:customer "cus_123" :base-url "https://parts.test"})]
    (testing "portal session carries only the customer and the return URL"
      (is (= {:customer   "cus_123"
              :return_url "https://parts.test/app/account"}
             params)))))

(deftest valid-signature?-test
  (let [secret  "whsec_test_secret"
        payload "{\"id\":\"evt_1\",\"type\":\"invoice.paid\"}"
        now     1690000000]
    (testing "accepts a genuine signature within tolerance"
      (is (true? (stripe/valid-signature? payload (sig-header secret now payload) secret
                                          {:now now}))))

    (testing "accepts when one of several v1 signatures matches"
      (let [header (str "t=" now
                        ",v1=" (hex-hmac secret "wrong.payload")
                        ",v1=" (hex-hmac secret (str now "." payload)))]
        (is (true? (stripe/valid-signature? payload header secret {:now now})))))

    (testing "ignores non-v1 scheme entries"
      (let [header (str "t=" now ",v0=" (hex-hmac secret (str now "." payload)))]
        (is (false? (stripe/valid-signature? payload header secret {:now now})))))

    (testing "rejects a tampered payload"
      (is (false? (stripe/valid-signature? "{\"id\":\"evt_evil\"}"
                                           (sig-header secret now payload)
                                           secret {:now now}))))

    (testing "rejects the wrong secret"
      (is (false? (stripe/valid-signature? payload (sig-header "whsec_other" now payload)
                                           secret {:now now}))))

    (testing "rejects a stale timestamp (replay protection)"
      (is (false? (stripe/valid-signature? payload (sig-header secret now payload) secret
                                           {:now (+ now 301)}))))

    (testing "accepts at the tolerance boundary"
      (is (true? (stripe/valid-signature? payload (sig-header secret now payload) secret
                                          {:now (+ now 300)}))))

    (testing "rejects a far-future timestamp"
      (is (false? (stripe/valid-signature? payload (sig-header secret (+ now 400) payload)
                                           secret {:now now}))))

    (testing "rejects malformed and missing headers"
      (is (false? (stripe/valid-signature? payload nil secret {:now now})))
      (is (false? (stripe/valid-signature? payload "" secret {:now now})))
      (is (false? (stripe/valid-signature? payload "garbage" secret {:now now})))
      (is (false? (stripe/valid-signature? payload "t=abc,v1=def" secret {:now now}))))))

(deftest parse-event-test
  (testing "parses event JSON with keyword keys"
    (is (= {:id   "evt_1"
            :type "checkout.session.completed"
            :data {:object {:customer "cus_1"}}}
           (stripe/parse-event
            "{\"id\":\"evt_1\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"customer\":\"cus_1\"}}}")))))
