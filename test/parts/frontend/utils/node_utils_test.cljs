(ns parts.frontend.utils.node-utils-test
  (:require [clojure.test :refer [deftest testing is]]
            [parts.frontend.utils.node-utils :as nu]
            [parts.common.part-types :refer [part-types]]))

(deftest build-updated-part-test
  (testing "when changing type with default label"
    (let [node {:type "manager"
                :data {:label "Manager"}}
          form {:type "unknown"
                :label "Manager"}
          result (nu/build-updated-part node form)]
      (is (= "unknown" (:type result)))
      (is (= {"label" "Unknown"} (:data result)))))

  (testing "when changing type with custom label"
    (let [node {:type "manager"
                :data {:label "My Custom Manager"}}
          form {:type "unknown"
                :label "My Custom Manager"}
          result (nu/build-updated-part node form)]
      (is (= "unknown" (:type result)))
      (is (= {"label" "My Custom Manager"} (:data result)))))

  (testing "when changing type and explicitly changing label"
    (let [node {:type "manager"
                :data {:label "Manager"}}
          form {:type "unknown"
                :label "Custom Unknown"}
          result (nu/build-updated-part node form)]
      (is (= "unknown" (:type result)))
      (is (= {"label" "Custom Unknown"} (:data result))))))
