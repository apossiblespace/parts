;; ---------------------------------------------------------
;; apossiblespace.parts.-test
;;
;; Example unit tests for apossiblespace.parts
;;
;; - `deftest` - test a specific function
;; - `testing` logically group assertions within a function test
;; - `is` assertion:  expected value then function call
;; ---------------------------------------------------------


(ns tools.ifs.parts-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tools.ifs.parts :as parts]))


;; (deftest application-test
;;   (testing "TODO: Start with a failing test, make it pass, then refactor"

;;     ;; TODO: fix greet function to pass test
;;     (is (= "apossiblespace application developed by the secret engineering team"
;;            (parts/greet)))

;;     ;; TODO: fix test by calling greet with {:team-name "Practicalli Engineering"}
;;     (is (= (parts/greet "Practicalli Engineering")
;;            "apossiblespace service developed by the Practicalli Engineering team"))))
