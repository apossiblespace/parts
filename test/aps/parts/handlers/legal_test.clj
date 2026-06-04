(ns aps.parts.handlers.legal-test
  (:require
   [aps.parts.handlers.legal :as legal]
   [aps.parts.middleware :as middleware]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- wrap [handler] (middleware/wrap-html-response handler))

(deftest page-test
  (testing "GET /privacy renders the example Privacy Policy in the document layout"
    (let [response ((wrap (legal/page "privacy")) {})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "Privacy Policy"))
      ;; No PDF is configured in the test environment, so no download link.
      (is (not (str/includes? (:body response) "Download PDF")))))

  (testing "an unknown slug renders a 404 not-published page"
    (let [response ((wrap (legal/page "bogus")) {})]
      (is (= 404 (:status response)))
      (is (str/includes? (:body response) "Not published")))))

(deftest download-test
  (testing "download 404s when the operator has supplied no PDF"
    (let [response ((wrap (legal/download "privacy")) {})]
      (is (= 404 (:status response))))))
