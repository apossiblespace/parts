(ns tools.ifs.parts.waitlist
  (:require
   [hiccup2.core :refer [html]]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]
   [tools.ifs.parts.db :as db]))

(defn signup
  "Register email address in private beta waitlist"
  [request]
  (db/insert! :waitlist_signups {:email (get-in request [:body :email])})
  (-> (response/response
       (html [:div#thankyou
              [:p "Thank you for your interest!"]]))
      (response/status 201)))
