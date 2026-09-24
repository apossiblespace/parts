(ns aps.parts.frontend.storage.ids-test
  (:require
   [aps.parts.frontend.storage.ids :as ids]
   [cljs.test :refer-macros [deftest is testing]]))

(deftest normalize-map-ids-test
  (let [u         (fn [] (random-uuid))
        [m p r e] (repeatedly 4 u)
        out       (ids/normalize-map-ids
                   {:id                   m
                    :parts                [{:id p :map_id m}]
                    :relationships        [{:id r :map_id m :source_id p :target_id p}]
                    :conversation_entries [{:id e :map_id m :part_id p}]})]
    (testing "every id the client joins on is a string, so lookups match"
      (is (= (str p) (-> out :parts first :id)))
      (is (= (str p) (-> out :relationships first :source_id)))
      (is (= (-> out :parts first :id)
             (-> out :conversation_entries first :part_id))
          "an entry's Part id matches its Part's id")
      (is (every? string? (vals (first (:conversation_entries out))))))))
