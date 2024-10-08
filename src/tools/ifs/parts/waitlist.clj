(ns tools.ifs.parts.waitlist
  (:require
   [hiccup2.core :refer [html]]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]
   [tools.ifs.parts.db :as db]
   [tools.ifs.parts.layouts.partials :as partials]
   [clojure.string :as str]))

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
