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

(defn- form-with-message
  "Re-render signup form with a message"
  [message]
  (html [:p "Please enter your email below to join the private beta test."]
    (partials/waitlist-signup-form ".signup")
    [:div.error
     [:p message]]))

(defn signup
  "Register email address in private beta waitlist"
  [request]
  (let [email (get-in request [:form-params "email"])]
    (cond
      (or (nil? email) (str/blank? email))
      (-> (response/response
            (form-with-message "Please don't forget your email address!"))
          (response/status 200))

      (not (valid-email? email))
      (-> (response/response
            (form-with-message "Sorry, that's not a valid email address."))
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
