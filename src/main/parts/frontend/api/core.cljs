(ns parts.frontend.api.core
  (:require [cljs.core.async :refer [<! go]]
            [cljs-http.client :as http]
            [parts.frontend.api.utils :as utils]))

(defn add-auth-header [req]
  (if-let [header (utils/get-auth-header)]
    (assoc-in req [:headers "Authorization"] header)
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
        (utils/save-tokens (:body response)))
      response)))

(defn logout []
  (js/localStorage.removeItem "auth-token"))

;; Account

(defn get-current-user []
  (GET "/account" {}))
