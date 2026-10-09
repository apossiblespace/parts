(ns aps.parts.email-layout
  "The layout of the emails that Parts sends to Users: Operator emails and
   transactional emails such as the password reset. See ADR-0020.

   The body is Markdown. The HTML part and the plain-text part are both
   made from the same sanitised HTML. The plain text writes a link as
   \"text (url)\". A footer ends both parts. A footer line is a string, or
   a vector of `[label url]` links."
  (:require
   [aps.parts.config :as conf]
   [aps.parts.legal :as legal]
   [clojure.string :as str]
   [hiccup2.core :as h]))

(defn privacy-link
  "Returns the `[label url]` link to the Privacy Policy."
  []
  ["Privacy Policy" (str (conf/base-url) "/privacy")])

(defn transactional-footer
  "Returns the footer lines of an email about the account of `to`. UK
   company law requires the company line in business emails. A
   transactional email has no unsubscribe link."
  [to]
  (remove nil?
          [(str "This email was sent to " to " about your Parts account.")
           [(privacy-link)]
           (conf/mail-sender-identity)]))

(defn- footer-text
  [lines]
  (str/join "\n" (mapcat #(if (string? %) [%] (for [[label url] %] (str label ": " url)))
                         lines)))

(defn- footer-html
  [lines]
  (for [line lines]
    [:p {:style "font-size:13px;line-height:1.5;color:#6b6b6b;margin:0 0 4px"}
     (if (string? line)
       line
       (interpose " · " (for [[label url] line]
                          [:a {:href url :style "color:#6b6b6b"} label])))]))

;; Mail clients ignore style sheets in many cases, so every style is inline.
(defn- html-document
  [body lines]
  (str
   (h/html
    (h/raw "<!DOCTYPE html>")
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]]
     ;; Many clients drop the styles of <body>, so it has none. Outlook for
     ;; Windows ignores max-width. Only Outlook reads the [if mso]
     ;; comments, which give it a fixed-width table instead.
     [:body
      (h/raw "<!--[if mso]><table role=\"presentation\" width=\"560\" align=\"center\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td><![endif]-->")
      [:div {:style (str "max-width:560px;margin:0 auto;padding:24px;color:#1f1f1f;"
                         "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;"
                         "font-size:16px;line-height:1.55")}
       (h/raw (legal/render-html body))
       [:div {:style "border-top:1px solid #e5e5e5;margin-top:32px;padding-top:12px"}
        (footer-html lines)]]
      (h/raw "<!--[if mso]></td></tr></table><![endif]-->")]])))

(defn content
  "Returns the `{:text :html}` parts of an email with the Markdown `body`
   and the footer `lines`."
  [body lines]
  {:text (str (legal/render-text body) "\n\n-- \n" (footer-text lines))
   :html (html-document body lines)})

(defn alternative
  "Returns the postal body of the `{:text :html}` parts: a
   multipart/alternative message with the plain text first."
  [{:keys [text html]}]
  [:alternative
   {:type "text/plain; charset=utf-8" :content text}
   {:type "text/html; charset=utf-8" :content html}])
