(ns aps.parts.frontend.state.windows-test
  (:require
   [aps.parts.frontend.state.windows :as windows]
   [cljs.test :refer-macros [deftest is testing]]))

(deftest open-close-test
  (testing "opening places the kind at its default position, in front"
    (is (= {:body-location {:pos [16 96] :z 1}}
           (windows/open nil :body-location))))

  (testing "reopening keeps the position and raises z above every window"
    (let [ws {:body-location {:pos [200 300] :z 1}
              :trigger       {:pos [10 10] :z 2}}]
      (is (= {:pos [200 300] :z 3}
             (:body-location (windows/open ws :body-location))))))

  (testing "closing removes the kind; closing a closed kind is a no-op"
    (is (= {} (windows/close {:body-location {:pos [1 1] :z 1}} :body-location)))
    (is (nil? (windows/close nil :body-location)))))

(deftest front-test
  (let [ws {:body-location {:pos [0 0] :z 1}
            :trigger       {:pos [0 0] :z 2}}]
    (testing "an open window comes to the front"
      (is (= 3 (get-in (windows/front ws :body-location) [:body-location :z]))))
    (testing "a closed kind is untouched"
      (is (= ws (windows/front ws :statements))))))

(deftest clamp-pos-test
  (let [size   {:width 100 :height 50}
        bounds {:width 800 :height 600}]
    (testing "a position inside the view is unchanged"
      (is (= [10 20] (windows/clamp-pos [10 20] size bounds))))
    (testing "past the right/bottom edge snaps the window fully inside"
      (is (= [700 550] (windows/clamp-pos [900 900] size bounds))))
    (testing "negative positions snap to the edge"
      (is (= [0 0] (windows/clamp-pos [-5 -5] size bounds))))
    (testing "a window larger than the view pins to the top-left"
      (is (= [0 0] (windows/clamp-pos [50 50] {:width 900 :height 700} bounds))))))

(deftest move-test
  (let [ws {:body-location {:pos [0 0] :z 1}}]
    (testing "moving clamps as it goes"
      (is (= [700 0]
             (get-in (windows/move ws :body-location [1000 -3]
                                   {:width 100 :height 50}
                                   {:width 800 :height 600})
                     [:body-location :pos]))))
    (testing "moving a closed kind is a no-op"
      (is (= ws (windows/move ws :trigger [1 1] {:width 1 :height 1}
                              {:width 9 :height 9}))))))

(deftest scope-part-test
  (testing "exactly one selected Part is the scope"
    (is (= {:id "a"} (windows/scope-part [{:id "a"}]))))
  (testing "none or several means no scope"
    (is (nil? (windows/scope-part [])))
    (is (nil? (windows/scope-part [{:id "a"} {:id "b"}])))))
