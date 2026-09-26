(ns aps.parts.frontend.api.batch-test
  (:require
   [aps.parts.frontend.api.batch :as batch]
   [cljs.core.async :refer [<! >! alts! chan close! go timeout]]
   [cljs.test :refer-macros [async deftest is]]))

(def ^:private opts {:idle-ms 40 :max-ms 100})

(defn- take-within
  "The next value from `ch` within `ms`, or ::none."
  [ch ms]
  (go (let [[v source] (alts! [ch (timeout ms)])]
        (if (= source ch) v ::none))))

(deftest idle-flush-test
  (async done
         (go (let [in  (chan)
                   out (batch/debounce-batch in opts)]
               (>! in :a)
               (>! in :b)
               (is (= ::none (<! (take-within out 20))) "nothing before the idle time")
               (is (= [:a :b] (<! (take-within out 60))) "sent after the idle time")
               (close! in)
               (done)))))

(deftest max-wait-flush-test
  (async done
         (go (let [in  (chan)
                   out (batch/debounce-batch in opts)]
               ;; A value every 20 ms for 140 ms never leaves an idle gap of
               ;; 40 ms; without the max wait nothing would come before 180 ms.
               (go (loop [n 0]
                     (when (< n 7)
                       (>! in n)
                       (<! (timeout 20))
                       (recur (inc n)))))
               (is (vector? (<! (take-within out 130))) "sent by the max wait despite continuous input")
               (close! in)
               (done)))))

(deftest flush-signal-test
  (async done
         (go (let [in  (chan)
                   out (batch/debounce-batch in opts)]
               (>! in :a)
               (>! in batch/flush-signal)
               (is (= [:a] (<! (take-within out 10))) "sent at once")
               (close! in)
               (done)))))

(deftest close-flush-test
  (async done
         (go (let [in  (chan)
                   out (batch/debounce-batch in opts)]
               (>! in :a)
               (close! in)
               (is (= [:a] (<! (take-within out 10))) "the rest is sent when the input closes")
               (is (nil? (<! (take-within out 10))) "then the output closes")
               (done)))))
