(ns parts.common.models.user-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [parts.common.models.user :as user]))

(deftest make-user-test
  (testing "Creates a valid user with minimal attributes"
    (let [result (user/make-user {:email "bob@bobson.net"
                                  :username "bobson"
                                  :display_name "Bob Bobson"
                                  :role "therapist"})]
      (is (string? (:id result)))
      (is (= "bob@bobson.net" (:email result)))
      (is (= "bobson" (:username result)))
      (is (= "Bob Bobson" (:display_name result)))
      (is (= "therapist" (:role result)))))

  (testing "Creates a user with provided attributes"
    (let [attrs {:id "cutom-id"
                 :email "robert@robertson.net"
                 :username "robertson"
                 :display_name "Robert Robertson Esq."
                 :role "client"}
          result (user/make-user attrs)]
      (is (= attrs result))))

  (testing "Creates a valid user with password + confirmation"
    (let [result (user/make-user {:email "bob@bobson.net"
                                  :username "bobson"
                                  :display_name "Bob Bobson"
                                  :role "therapist"
                                  :password "pass1234"
                                  :password_confirmation "pass1234"}
                                 true)]
      (is (string? (:id result)))
      (is (= "bob@bobson.net" (:email result)))
      (is (= "bobson" (:username result)))
      (is (= "Bob Bobson" (:display_name result)))
      (is (= "therapist" (:role result)))))

  (testing "Throws an exception for missing required attribute"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (user/make-user {}))))

  (testing "Throws an exception for mismatching password confirmation"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo) #"Validation failed"
         (user/make-user {:email "bob@bobson.net"
                          :username "bobson"
                          :display_name "Bob Bobson"
                          :role "therapist"
                          :password "pass1234"
                          :password_confirmation "something"}
                         true)))))
