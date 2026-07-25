(ns aps.parts.ratelimit-test
  (:require
   [aps.parts.ratelimit :as rl]
   [clojure.test :refer [deftest is testing]]))

(deftest step-test
  (testing "a fresh bucket starts full and allows up to capacity, then denies"
    (let [c  3
          r  0.0                                  ; no refill, frozen clock
          b1 (rl/step nil 0 c r)
          b2 (rl/step b1 0 c r)
          b3 (rl/step b2 0 c r)
          b4 (rl/step b3 0 c r)]
      (is (= [true true true false]
             [(:allowed? b1) (:allowed? b2) (:allowed? b3) (:allowed? b4)]))))

  (testing "tokens refill over elapsed time"
    (let [c       1
          r       (/ 1.0 1000)                         ; 1 token per 1000ms
          b1      (rl/step nil 0 c r)                 ; consume the one token at t=0
          denied  (rl/step b1 500 c r)            ; 0.5 token at t=500 → denied
          allowed (rl/step b1 1000 c r)]         ; 1 token back at t=1000 → allowed
      (is (true? (:allowed? b1)))
      (is (false? (:allowed? denied)))
      (is (true? (:allowed? allowed))))))

(defn- ok-handler [_] {:status 200 :body "ok"})

(defn- mk [route-key store now]
  ((rl/limiter route-key {:capacity 2 :refill-per-ms 0.0 :now-ms (constantly now) :store store})
   ok-handler))

(deftest limiter-middleware-test
  (testing "denies with 429 after the burst is exhausted (AC#1/#2)"
    (let [store   (atom {})
          handler (mk :login store 0)
          req     {:headers {"x-real-ip" "203.0.113.7"}}
          rs      (repeatedly 3 #(handler req))]
      (is (= [200 200 429] (map :status rs)))
      (is (= "60" (get-in (last rs) [:headers "Retry-After"])))))

  (testing "buckets are independent per client IP (AC#3 — one user can't lock out another)"
    (let [store   (atom {})
          handler (mk :login store 0)
          a       {:headers {"x-real-ip" "198.51.100.1"}}
          b       {:headers {"x-real-ip" "198.51.100.2"}}]
      (dotimes [_ 3] (handler a))               ; exhaust A
      (is (= 200 (:status (handler b))) "B is unaffected by A's flood")))

  (testing "buckets are independent per route-key"
    (let [store (atom {})
          login (mk :login store 0)
          reg   (mk :register store 0)
          req   {:headers {"x-real-ip" "203.0.113.9"}}]
      (dotimes [_ 3] (login req))               ; exhaust login
      (is (= 200 (:status (reg req))) "register has its own bucket")))

  (testing "falls back to remote-addr when the trusted header is absent"
    (let [store   (atom {})
          handler (mk :login store 0)
          req     {:remote-addr "10.0.0.5"}]
      (is (= [200 200 429] (map :status (repeatedly 3 #(handler req))))))))

(defn- mk-user [route-key store now]
  ((rl/user-limiter route-key {:capacity 2 :refill-per-ms 0.0 :now-ms (constantly now) :store store})
   ok-handler))

(deftest user-limiter-test
  (testing "sustained rapid writes from one user are throttled"
    (let [store   (atom {})
          handler (mk-user :changes store 0)
          req     {:identity {:sub "user-a"}}]
      (is (= [200 200 429] (map :status (repeatedly 3 #(handler req)))))))

  (testing "independent users do not share a bucket"
    (let [store   (atom {})
          handler (mk-user :changes store 0)
          a       {:identity {:sub "user-a"}}
          b       {:identity {:sub "user-b"}}]
      (dotimes [_ 3] (handler a))
      (is (= 200 (:status (handler b))) "B is unaffected by A's flood")))

  (testing "keys on user identity, not client IP"
    (let [store   (atom {})
          handler (mk-user :changes store 0)
          req     (fn [n] {:identity {:sub "user-a"}
                           :headers  {"x-real-ip" (str "198.51.100." n)}})
          rs      (mapv #(handler (req %)) (range 3))]
      (is (= [200 200 429] (mapv :status rs))
          "rotating IPs does not grant the same user fresh buckets"))))

(deftest spoofed-header-test
  (testing "rotating X-Forwarded-For never grants a fresh bucket (TASK-088 bypass)"
    (let [store   (atom {})
          handler (mk :login store 0)
          req     (fn [n] {:headers {"x-real-ip"       "203.0.113.7"
                                     "x-forwarded-for" (str "198.51.100." n)}})
          rs      (mapv #(handler (req %)) (range 5))]
      (is (= [200 200 429 429 429] (mapv :status rs))
          "the limiter keys on the proxy-set header, ignoring the spoofable one")))

  (testing "with no trusted header, rotating X-Forwarded-For collapses to one shared bucket"
    (let [store   (atom {})
          handler (mk :login store 0)
          req     (fn [n] {:headers     {"x-forwarded-for" (str "198.51.100." n)}
                           :remote-addr "127.0.0.1"})
          rs      (mapv #(handler (req %)) (range 5))]
      (is (= [200 200 429 429 429] (mapv :status rs))
          "misconfiguration over-throttles; it never opens a bypass"))))
