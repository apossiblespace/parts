(ns tools.ifs.parts.waitlist
  (:require
   [hiccup2.core :refer [html]]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]
   [tools.ifs.parts.db :as db]
   [tools.ifs.parts.layouts.partials :as partials]
   [clojure.string :as str]))

(defn signup
  "Register email address in private beta waitlist"
  [request]
  (let [email (get-in request [:form-params "email"])]
    (if (and email (not (str/blank? email)))
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
              (response/status 200))))
      (-> (response/response
           (html (partials/waitlist-signup-form "#signup-form")
             [:div.error
              [:p "Please don't forget your email address!"]]))
          (response/status 200)))))
