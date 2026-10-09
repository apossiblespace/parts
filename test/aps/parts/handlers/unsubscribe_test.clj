(ns aps.parts.handlers.unsubscribe-test
  (:require
   [aps.parts.db :as db]
   [aps.parts.helpers.utils :refer [create-test-user! with-test-db]]
   [aps.parts.server :as server]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-test-db)

(defn- user-row [id]
  (db/query-one (db/sql-format {:select [:product_updates_opted_out_at :unsubscribe_token]
                                :from   [:users]
                                :where  [:= :id id]})))

(defn- url [token] (str "/unsubscribe/" token))

(deftest test-unsubscribe-flow
  (let [app   (server/app)
        id    (:id (create-test-user!))
        token (:unsubscribe_token (user-row id))]
    (testing "get shows the confirm page and does not unsubscribe"
      (let [response (app (mock/request :get (url token)))]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "Unsubscribe from product updates"))
        (is (nil? (:product_updates_opted_out_at (user-row id))))))

    (testing "post without an anti-forgery token unsubscribes and redirects to get"
      (let [response (app (mock/request :post (url token)))]
        (is (= 303 (:status response)))
        (is (= (url token) (get-in response [:headers "Location"])))
        (is (some? (:product_updates_opted_out_at (user-row id))))))

    (testing "get then shows the result"
      (is (str/includes? (:body (app (mock/request :get (url token))))
                         "You are unsubscribed")))))

(deftest test-one-click-unsubscribe
  (let [app   (server/app)
        id    (:id (create-test-user!))
        token (:unsubscribe_token (user-row id))]
    (testing "an rfc 8058 one-click post unsubscribes"
      (app (-> (mock/request :post (url token))
               (mock/body {"List-Unsubscribe" "One-Click"})))
      (is (some? (:product_updates_opted_out_at (user-row id)))))))

(deftest test-unknown-token
  (let [app (server/app)]
    (testing "an unknown or malformed token gets a neutral 404 page"
      (doseq [token [(random-uuid) "not-a-uuid"]]
        (let [response (app (mock/request :get (url token)))]
          (is (= 404 (:status response)))
          (is (str/includes? (:body response) "This link is not valid")))))
    (testing "a post with an unknown token redirects without an error"
      (is (= 303 (:status (app (mock/request :post (url (random-uuid))))))))))
