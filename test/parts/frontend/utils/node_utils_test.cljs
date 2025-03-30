(ns parts.frontend.utils.node-utils-test
  (:require [clojure.test :refer [deftest testing is]]
            [parts.frontend.utils.node-utils :as nu]))

(deftest build-updated-part-test
  (testing "when changing type with default label"
    (let [node {:data {:type "manager" :label "Manager"}}
          form {:type "unknown"
                :label "Manager"}
          result (nu/build-updated-part node form)]
      (is (= {:data {:type "unknown"
                     :label "Manager"}}
             result))))

  (testing "when changing type with custom label"
    (let [node {:data {:type "manager" :label "My Custom Manager"}}
          form {:type "unknown"
                :label "My Custom Manager"}
          result (nu/build-updated-part node form)]
      (is (= {:data {:type "unknown"
                     :label "My Custom Manager"}}
             result))))

  (testing "when changing type and explicitly changing label"
    (let [node {:data {:type "manager" :label "Manager"}}
          form {:type "unknown"
                :label "Custom Unknown"}
          result (nu/build-updated-part node form)]
      (is (= {:data {:type "unknown"
                     :label "Custom Unknown"}}
             result)))))
