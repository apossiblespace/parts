(ns aps.parts.frontend.state.windows-test
  (:require
   [aps.parts.frontend.state.windows :as windows]
   [cljs.test :refer-macros [deftest is testing]]))

(deftest open-close-test
  (testing "opening places the kind at its default position, in front"
    (is (= {:open? true :pos [16 96] :z 1}
           (:body-location (windows/open nil :body-location)))))

  (testing "each kind has its own default, so two windows do not stack exactly"
    (is (not= (get-in (windows/open nil :body-location) [:body-location :pos])
              (get-in (windows/open nil :trigger) [:trigger :pos]))))

  (testing "reopening keeps the position and raises z above every window"
    (let [ws {:body-location {:open? true :pos [200 300] :z 1}
              :trigger       {:open? true :pos [10 10] :z 2}}]
      (is (= {:open? true :pos [200 300] :z 3}
             (:body-location (windows/open ws :body-location))))))

  (testing "closing hides the kind but keeps position, size and drafts"
    (let [ws (-> nil
                 (windows/open :notes)
                 (windows/set-draft :notes "p1" "draft")
                 (windows/close :notes))]
      (is (false? (windows/open? ws :notes)))
      (is (= [] (windows/open-kinds ws)))
      (is (= [28 112] (get-in ws [:notes :pos])))
      (is (= "draft" (windows/draft ws :notes "p1")))
      (is (= [28 112] (get-in (windows/open ws :notes) [:notes :pos])))))

  (testing "closing a never-opened kind is a no-op"
    (is (nil? (windows/close nil :body-location))))

  (testing "open-kinds lists only the open ones"
    (is (= [:trigger]
           (windows/open-kinds {:notes   {:open? false}
                                :trigger {:open? true}})))))

(deftest front-test
  (let [ws {:body-location {:open? true :pos [0 0] :z 1}
            :trigger       {:open? true :pos [0 0] :z 2}}]
    (testing "an open window comes to the front"
      (is (= 3 (get-in (windows/front ws :body-location) [:body-location :z]))))
    (testing "a closed kind is untouched"
      (is (= ws (windows/front ws :statements)))
      (let [closed (assoc-in ws [:trigger :open?] false)]
        (is (= closed (windows/front closed :trigger)))))))

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
  (let [ws {:body-location {:open? true :pos [0 0] :z 1}}]
    (testing "moving clamps as it goes"
      (is (= [700 0]
             (get-in (windows/move ws :body-location [1000 -3]
                                   {:width 100 :height 50}
                                   {:width 800 :height 600})
                     [:body-location :pos]))))
    (testing "moving a closed kind is a no-op"
      (is (= ws (windows/move ws :trigger [1 1] {:width 1 :height 1}
                              {:width 9 :height 9}))))))

(deftest resize-test
  (let [ws     {:notes {:open? true :pos [100 100] :z 1}}
        bounds {:width 800 :height 600}]
    (testing "a size inside the view is kept"
      (is (= [300 200] (get-in (windows/resize ws :notes [300 200] bounds) [:notes :size]))))
    (testing "never smaller than min-size"
      (is (= windows/min-size (get-in (windows/resize ws :notes [10 10] bounds) [:notes :size]))))
    (testing "never past the view edge from the window's position"
      (is (= [700 500] (get-in (windows/resize ws :notes [5000 5000] bounds) [:notes :size]))))
    (testing "resizing a closed kind is a no-op"
      (is (= ws (windows/resize ws :trigger [300 300] bounds))))))

(deftest draft-test
  (testing "drafts are keyed by entity; set, read, clear"
    (let [ws (-> nil
                 (windows/set-draft :notes "p1" "one")
                 (windows/set-draft :notes "p2" "two"))]
      (is (= "one" (windows/draft ws :notes "p1")))
      (is (= "two" (windows/draft ws :notes "p2")))
      (is (nil? (windows/draft ws :notes "p3")))
      (is (nil? (windows/draft (windows/clear-draft ws :notes "p1") :notes "p1")))
      (is (= "two" (windows/draft (windows/clear-draft ws :notes "p1") :notes "p2")))))
  (testing "a draft may hold nil-valued content when wrapped by the consumer"
    (is (= {:location nil}
           (windows/draft (windows/set-draft nil :body-location "p1" {:location nil})
                          :body-location "p1"))))
  (testing "clearing on an unknown kind is a no-op"
    (is (nil? (windows/clear-draft nil :notes "p1")))))

(deftest scope-part-test
  (testing "exactly one selected Part is the scope"
    (is (= {:id "a"} (windows/scope-part [{:id "a"}]))))
  (testing "none or several means no scope"
    (is (nil? (windows/scope-part [])))
    (is (nil? (windows/scope-part [{:id "a"} {:id "b"}])))))

(deftest scope-notes-test
  (testing "a Part wins over a Relationship selected with it"
    (is (= [:part {:id "a"}] (windows/scope-notes [{:id "a"}] [{:id "r"}]))))
  (testing "a Relationship is the scope only when selected alone"
    (is (= [:relationship {:id "r"}] (windows/scope-notes [] [{:id "r"}])))
    (is (nil? (windows/scope-notes [{:id "a"} {:id "b"}] [{:id "r"}])))
    (is (nil? (windows/scope-notes [] [{:id "r"} {:id "s"}])))))
