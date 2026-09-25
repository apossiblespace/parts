(ns aps.parts.frontend.components.inline-edit-test
  (:require
   [aps.parts.frontend.components.inline-edit :refer [commit-value resync]]
   [cljs.test :refer-macros [deftest is testing]]
   [clojure.string :as str]))

(def ^:private non-blank (complement str/blank?))

(deftest commit-value-commit-test
  (testing "a valid, changed draft commits its trimmed value"
    (is (= "New label" (commit-value "New label" "Old" non-blank)))
    (is (= "New label" (commit-value "  New label  " "Old" non-blank))
        "leading/trailing whitespace is trimmed")))

(deftest commit-value-cancel-test
  (testing "a no-op (trimmed draft equals current value) cancels — returns nil"
    (is (nil? (commit-value "Old" "Old" non-blank)))
    (is (nil? (commit-value "  Old  " "Old" non-blank))
        "trims before the no-op comparison")))

(deftest commit-value-empty-test
  (testing "a blank/whitespace draft cancels — the name is never destroyed"
    (is (nil? (commit-value "" "Old" non-blank)))
    (is (nil? (commit-value "   " "Old" non-blank)))))

(deftest commit-value-validate-test
  (testing "a draft that fails a custom validate predicate cancels"
    (let [min-six (fn [s] (>= (count s) 6))]
      (is (nil? (commit-value "abc" "Old" min-six)))
      (is (= "abcdef" (commit-value "abcdef" "Old" min-six))))))

(deftest test-resync
  (let [state {:values {:notes "new"} :initial {:notes "new"}}]
    (testing "a commit's echo not yet in the store keeps the committed value"
      (is (identical? state (resync state {:notes "old"} {:notes "old"}))))
    (testing "a value saved elsewhere lands when the field is not mid-edit"
      (is (= {:values {:notes "window"} :initial {:notes "window"}}
             (resync state {:notes "new"} {:notes "window"}))))
    (testing "a field mid-edit keeps the user's text"
      (let [editing (assoc-in state [:values :notes] "typing")]
        (is (identical? editing (resync editing {:notes "new"} {:notes "window"})))))))
