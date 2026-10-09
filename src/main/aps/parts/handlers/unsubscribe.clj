(ns aps.parts.handlers.unsubscribe
  "Server-rendered pages for the unsubscribe link in every Product update:
   GET and POST `/unsubscribe/:token`.

   The token is the credential, and it does not expire. GET changes
   nothing, because corporate mail scanners open every link in an email.
   POST unsubscribes and redirects to GET. A mail client that supports
   RFC 8058 one-click unsubscribe sends the same POST. See ADR-0020."
  (:require
   [aps.parts.entity.user :as user]
   [aps.parts.views.layouts :as layouts]
   [aps.parts.views.partials :as partials]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]))

(defn show
  "GET /unsubscribe/:token. Returns the confirm or result page, or a 404
   page for an unknown token."
  [request]
  (let [token (get-in request [:path-params :token])]
    (if-let [{:keys [product_updates_opted_out_at]} (user/find-by-unsubscribe-token token)]
      (response/response
       (layouts/content-page "Unsubscribe"
                             (partials/unsubscribe-content
                              {:token      token
                               :opted-out? (some? product_updates_opted_out_at)})))
      (-> (response/response
           (layouts/content-page "Link not valid"
                                 (partials/unsubscribe-unavailable-content)))
          (response/status 404)))))

;; The POST renders no page, so it needs none of the session and
;; anti-forgery middleware that the layout depends on. An unknown token
;; redirects too, and GET shows the 404 page.
(defn confirm
  "POST /unsubscribe/:token. Opts out the user of the token and redirects
   to GET with 303."
  [request]
  (let [token (get-in request [:path-params :token])]
    (mulog/log ::unsubscribe :found? (user/unsubscribe! token))
    (-> (response/redirect (str "/unsubscribe/" token))
        (response/status 303))))
