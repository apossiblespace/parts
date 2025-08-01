(ns parts.handlers.waitlist
  (:require
   [clojure.string :as str]
   [com.brunobonacci.mulog :as mulog]
   [hiccup.util :refer [raw-string]]
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
            (partials/waitlist-signup-form
             {:message "Please don't forget your email address!"})))
          (response/status 400))

      (not (valid-email? email))
      (-> (response/response
           (html
            (partials/waitlist-signup-form
             {:message "Sorry, that's not a valid email address."
              :value email})))
          (response/status 400))

      :else
      (try
        (db/insert! :waitlist_signups {:email email})
        (mulog/log ::waitlist_signup :email email)
        (-> (response/response
             (html [:div.success
                    [:div {:class "text-6xl mb-2"} "🎉"]
                    [:p "Thank you for your interest in Parts! We'll be in touch soon."]
                    [:script (raw-string
                              "const element = document.getElementById('counter');
                            const currentValue = parseInt(element.textContent) || 0;
                            element.textContent = currentValue + 1;")]]))
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
                                        :from [:waitlist_signups]}))))
