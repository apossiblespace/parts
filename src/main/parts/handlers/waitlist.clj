(ns parts.handlers.waitlist
  (:require
   [clojure.string :as str]
   [com.brunobonacci.mulog :as mulog]
   [hiccup2.core :refer [html]]
   [parts.db :as db]
   [parts.views.partials :as partials]
   [ring.util.response :as response]))

(defn- valid-email?
  "Check if the email is valid"
  [email]
  (re-matches #".+@.+\..+" email))

(defn signup
  "Register email address in private beta waitlist"
  [request]
  (let [email (get-in request [:form-params "email"])]
    (cond
      (or (nil? email) (str/blank? email))
      (-> (response/response
           (html
            (partials/waitlist-signup-form "Please don't forget your email address!")))
          (response/status 200))

      (not (valid-email? email))
      (-> (response/response
           (html
            (partials/waitlist-signup-form "Sorry, that's not a valid email address.")))
          (response/status 200))

      :else
      (try
        (db/insert! :waitlist_signups {:email email})
        (mulog/log ::waitlist_signup :email email)
        (-> (response/response
             (html [:div.success
                    [:p "Thank you for your interest! We'll be in touch soon."]]))
            (response/status 201))
        (catch Exception _e
          (-> (response/response
               (html [:div.success
                      [:p "You're already on the list! We'll be in touch soon."]]))
              (response/status 200)))))))

(defn signups-count
  "Get the number of current signups on the waiting list"
  []
  (:total (db/query-one (db/sql-format {:select [[:%count.* :total]]
                                        :from [:waitlist_signups]}))))
