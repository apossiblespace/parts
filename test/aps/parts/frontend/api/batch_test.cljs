(ns aps.parts.frontend.api.batch-test
  (:require
   [aps.parts.frontend.api.batch :as batch]
   [cljs.test :refer-macros [async deftest is]]))

(defn- recording-batcher
  "Returns `[sent batcher]`. The batcher has short waits, and `sent` is an
   atom that collects its batches."
  []
  (let [sent (atom [])]
    [sent (batch/batcher {:idle-ms 40 :max-ms 100 :on-batch #(swap! sent conj %)})]))

(deftest test-idle-flush
  (async done
         (let [[sent b] (recording-batcher)]
           (batch/add! b :a)
           (batch/add! b :b)
           (js/setTimeout #(is (= [] @sent) "nothing before the idle time") 20)
           (js/setTimeout (fn []
                            (is (= [[:a :b]] @sent) "sent after the idle time")
                            (done))
                          70))))

(deftest test-max-wait-flush
  (async done
         (let [[sent b] (recording-batcher)
               ;; An event every 10 ms leaves no idle gap of 40 ms. Only the
               ;; max wait of 100 ms can send a batch.
               tick     (js/setInterval #(batch/add! b :x) 10)]
           (js/setTimeout (fn []
                            (js/clearInterval tick)
                            (is (seq @sent) "sent by the max wait despite continuous input")
                            (batch/take! b)
                            (done))
                          180))))

(deftest test-flush
  (let [[sent b] (recording-batcher)]
    (batch/add! b :a)
    (batch/flush! b)
    (is (= [[:a]] @sent) "sent at once")
    (batch/flush! b)
    (is (= [[:a]] @sent) "an empty flush sends nothing")))

(deftest test-take-is-synchronous-and-final
  (async done
         (let [[sent b] (recording-batcher)]
           (batch/add! b :a)
           (batch/add! b :b)
           (is (= [:a :b] (batch/take! b)) "the pending events, at once")
           (is (nil? (batch/take! b)) "taken events are no longer pending")
           (batch/flush! b)
           (js/setTimeout (fn []
                            (is (= [] @sent) "no flush or timer sends taken events again")
                            (batch/add! b :c)
                            (batch/flush! b)
                            (is (= [[:c]] @sent) "later events batch as normal")
                            (done))
                          120))))
