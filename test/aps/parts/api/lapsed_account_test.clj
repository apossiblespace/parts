(ns aps.parts.api.lapsed-account-test
  "A lapsed account through the full middleware stack (ADR-0021): every
   change to Map content is refused, every read, export and archive still
   works, and paying again restores editing at once."
  (:require
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [aps.parts.entity.session :as session]
   [aps.parts.helpers.browser :refer [api browse decode sign-in]]
   [aps.parts.helpers.utils :refer [create-test-map! create-test-user! with-test-db]]
   [aps.parts.server :as server]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.time LocalDate OffsetDateTime ZoneOffset)))

(use-fixtures :once with-test-db)

(defn- days-ago [n] (.minusDays (LocalDate/now ZoneOffset/UTC) n))

(defn- signed-in-user
  "A signed-in user created 60 days ago with one Map, and a `call` fn for
   the API."
  []
  (let [app            (server/app)
        pw             "correct horse battery"
        user           (create-test-user! {:password pw :password_confirmation pw})
        the-map        (create-test-map! (:id user))
        _              (db/update! :users {:created_at (.minusDays (OffsetDateTime/now ZoneOffset/UTC) 60)}
                                   [:= :id (:id user)])
        session        (session/create! (:id the-map) (:id user))
        [cookie token] (sign-in app user pw)]
    {:user       user
     :session-id (str (:id session))
     :call       (fn [method path & [body]]
                   (first (browse app (api method path token body) cookie)))
     :map-path   (str "/api/maps/" (:id the-map))}))

(deftest test-lapsed-account-is-read-only
  ;; Start date 40 days ago: the trial ended 30 days after creation.
  (with-redefs [conf/payment-start-date (constantly (days-ago 40))]
    (let [{:keys [user call map-path] at :session-id} (signed-in-user)
          session-id                                  (str (random-uuid))]
      (testing "every change to Map content is refused with 402"
        (doseq [[method path body] [[:post "/api/maps" {:title "New"}]
                                    [:put map-path {:title "Renamed"}]
                                    [:post (str map-path "/changes") []]
                                    [:post (str map-path "/sessions") {}]
                                    [:put (str map-path "/sessions/" session-id) {:trigger "x"}]
                                    [:delete (str map-path "/sessions/" session-id)]
                                    [:put (str map-path "/sessions/" session-id "/activation") {:part_id "x"}]
                                    [:delete (str map-path "/sessions/" session-id "/activation")]]]
          (let [response (call method path body)]
            (is (= 402 (:status response)) (str method " " path))
            (is (re-find #"read-only" (str (:error (decode response)))) (str method " " path)))))

      (testing "every read and export still works"
        (doseq [path ["/api/maps" map-path (str map-path "?at=" at) (str map-path "/sessions")
                      (str map-path "/preview.svg") (str map-path "/render.pdf")
                      (str map-path "/export.json") "/api/account" "/api/account/export.zip"]]
          (is (= 200 (:status (call :get path))) path)))

      (testing "archiving and unarchiving still work"
        (is (= 200 (:status (call :put (str map-path "/archive")))))
        (is (= 200 (:status (call :delete (str map-path "/archive"))))))

      (testing "paying again restores editing at once"
        (db/update! :users {:stripe_customer_id "cus_lapsed" :stripe_subscription_status "active"}
                    [:= :id (:id user)])
        (is (= 200 (:status (call :put map-path {:title "Renamed"}))))))))

(deftest test-nothing-blocked-before-the-start-date
  (testing "before the payment start date even an old account can edit"
    (with-redefs [conf/payment-start-date (constantly (.plusDays (LocalDate/now ZoneOffset/UTC) 1))]
      (let [{:keys [call map-path]} (signed-in-user)]
        (is (= 200 (:status (call :put map-path {:title "Renamed"}))))
        (is (= 201 (:status (call :post "/api/maps" {:title "Another"}))))))))

(deftest test-nothing-blocked-without-a-start-date
  (testing "with no payment start date no account is ever read-only"
    (with-redefs [conf/payment-start-date (constantly nil)]
      (let [{:keys [call map-path]} (signed-in-user)]
        (is (= 200 (:status (call :put map-path {:title "Renamed"}))))))))
