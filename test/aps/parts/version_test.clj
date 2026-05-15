(ns aps.parts.version-test
  (:require
   [aps.parts.version :as version]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(deftest test-current-falls-back-to-dev-when-resource-missing
  (testing "Returns the literal \"dev\" when no version resource is on the classpath"
    (with-redefs [io/resource (constantly nil)]
      (is (= "dev" (version/current))))))

(deftest test-current-reads-and-trims-resource-content
  (testing "Slurps the resource and trims surrounding whitespace / trailing newline"
    (with-redefs [io/resource (constantly (java.io.StringReader. "abc1234\n"))]
      (is (= "abc1234" (version/current))))))
