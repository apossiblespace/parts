(ns aps.parts.handlers.account-deleted
  "The page the Account page sends a User to after they delete their
   account: GET `/account-deleted`."
  (:require
   [aps.parts.views.layouts :as layouts]
   [aps.parts.views.partials :as partials]
   [ring.util.response :as response]))

(defn show
  "GET /account-deleted. The same page for everyone: it reads no session
   and says nothing about any account."
  [_request]
  (response/response
   (layouts/content-page "Account deleted" (partials/account-deleted-content))))
