(ns parts.frontend.api.core
  (:require [cljs.core.async :refer [<! go]]
            [cljs-http.client :as http]))

;; Helpers

(defn get-auth-token []
  (js/localStorage.getItem "auth-token"))

(defn add-auth-header [req]
  (if-let [token (js/localStorage.getItem "auth-token")]
    (assoc-in req [:headers "Authorization"] (str "Bearer " token))
    req))

(defn GET [endpoint params]
  (go (<! (http/get (str "/api" endpoint)
                    (-> {:query-params params
                         :accept :transit+json}
                        add-auth-header)))))

(defn POST [endpoint data]
  (go (<! (http/post (str "/api" endpoint)
                     (-> {:transit-params data
                          :accept :transit+json}
                         add-auth-header)))))

(defn PUT [endpoint data]
  (go (<! (http/put (str "/api" endpoint)
                    (-> {:transit-params data
                         :accept :transit+json}
                        add-auth-header)))))

(defn PATCH [endpoint data]
  (go (<! (http/patch (str "/api" endpoint)
                      (-> {:transit-params data
                           :accept :transit+json}
                          add-auth-header)))))

(defn DELETE [endpoint]
  (go (<! (http/delete (str "/api" endpoint)
                       (-> {:accept :transit+json}
                           add-auth-header)))))

;; Auth

(defn login [credentials]
  (go
    (let [response (<! (POST "/auth/login" credentials))]
      (when (= 200 (:status response))
        (js/localStorage.setItem "auth-token" (get-in response [:body :token])))
      response)))

(defn logout []
  (js/localStorage.removeItem "auth-token"))

;; Account

(defn get-current-user []
  (GET "/account" {}))
