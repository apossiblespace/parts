(ns aps.parts.handlers.waitlist
  (:require
   [aps.parts.common.utils :refer [normalize-email]]
   [aps.parts.db :as db]
   [aps.parts.views.partials :as partials]
   [clojure.string :as str]
   [com.brunobonacci.mulog :as mulog]
   [hiccup2.core :refer [html]]
   [ring.util.response :as response]))

(defn- valid-email?
  "Shape check on a normalized address: bounded length (254 per RFC 5321),
   no whitespace or control characters (which also excludes header-injection
   newlines), one @ with a dotted domain. Full RFC validation is
   deliberately not attempted."
  [email]
  (and (<= (count email) 254)
       (re-matches #"[^@\s\p{Cntrl}]+@[^@\s\p{Cntrl}]+\.[^@\s\p{Cntrl}]+" email)))

(defn signup
  "Register email address in private beta waitlist"
  [request]
  (let [email (some-> (get-in request [:form-params "email"]) normalize-email)]
    (cond
      (or (nil? email) (str/blank? email))
      (-> (response/response
           (html
            (partials/waitlist-signup-form
             {:message "Please don't forget your email address!"})))
          (response/status 400))

      (not (valid-email? email))
      (-> (response/response
           (html
            (partials/waitlist-signup-form
             {:message "Sorry, that's not a valid email address."
              :value   email})))
          (response/status 400))

      :else
      (try
        (db/insert! :waitlist_signups {:email email})
        (mulog/log ::waitlist_signup :email email)
        ;; No inline script (CSP): marketing.js sees the swapped-in
        ;; data-counter-increment and bumps the visible counter.
        (-> (response/response
             (html [:div.success {:data-counter-increment "true"}
                    [:div {:class "text-6xl mb-2"} "🎉"]
                    [:p "Thank you for your interest in Parts! We'll be in touch soon."]]))
            (response/status 201))
        (catch Exception _e
          (-> (response/response
               (html [:div.success
                      [:div {:class "text-6xl mb-2"} "👋"]
                      [:p "You're already on the list! We'll be in touch soon."]]))
              (response/status 409)))))))

(defn signups-count
  "Get the number of current signups on the waiting list"
  []
  (:total (db/query-one (db/sql-format {:select [[:%count.* :total]]
                                        :from   [:waitlist_signups]}))))
