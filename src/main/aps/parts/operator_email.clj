(ns aps.parts.operator-email
  "Builds and sends the emails that the operator writes to Users: Product
   updates and Service notices. See ADR-0020 and `CONTEXT.md`.

   The Operator console calls this namespace, and it calls `aps.parts.mail`
   to send. One function, `message`, builds the email for the preview, the
   test send and the real send, so the preview always shows what is sent.

   A draft is a map of `:kind`, `:subject` and `:body`. The kind is
   \"product-update\" or \"service-notice\", and the body is Markdown. The
   email has an HTML part and a plain-text part, both made from the same
   sanitised HTML. The plain text writes a link as \"text (url)\". A footer
   that the operator cannot edit ends both parts."
  (:require
   [aps.parts.config :as conf]
   [aps.parts.legal :as legal]
   [aps.parts.mail :as mail]
   [clojure.string :as str]
   [hiccup2.core :as h])
  (:import
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)
   (java.util HexFormat)))

(def kinds #{"product-update" "service-notice"})

(defn validate
  "Returns a message that says what is wrong with `draft`, or nil when the
   draft can be sent."
  [{:keys [subject body]}]
  (cond
    (str/blank? subject) "Write a subject."
    (str/blank? body)    "Write the email."))

(defn draft-hash
  "Returns a hex SHA-256 of the kind, subject and body of `draft`. Two
   drafts have the same hash only when all three are equal."
  [{:keys [kind subject body]}]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (str kind "\u0000" subject "\u0000" body)
                                  StandardCharsets/UTF_8))))

;;; Content

(defn- product-update? [kind] (= kind "product-update"))

(defn- footer-text
  [kind unsubscribe-url]
  (if (product-update? kind)
    (str "You get product updates because you have a Parts account.\n"
         "Unsubscribe: " unsubscribe-url)
    "This is a service notice about your Parts account."))

(defn- footer-html
  [kind unsubscribe-url]
  (if (product-update? kind)
    (list "You get product updates because you have a Parts account. "
          [:a {:href unsubscribe-url :style "color:#6b6b6b"} "Unsubscribe"] ".")
    "This is a service notice about your Parts account."))

;; Mail clients ignore style sheets in many cases, so every style is inline.
(defn- html-document
  [kind body unsubscribe-url]
  (str
   (h/html
    (h/raw "<!DOCTYPE html>")
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]]
     [:body {:style "margin:0;padding:0;background:#ffffff"}
      [:div {:style (str "max-width:560px;margin:0 auto;padding:24px;"
                         "font:16px/1.55 -apple-system,BlinkMacSystemFont,'Segoe UI',"
                         "Helvetica,Arial,sans-serif;color:#1f1f1f")}
       (h/raw (legal/render-html body))
       [:hr {:style "border:0;border-top:1px solid #e5e5e5;margin:32px 0 12px"}]
       [:p {:style "font-size:13px;line-height:1.5;color:#6b6b6b;margin:0"}
        (footer-html kind unsubscribe-url)]]]])))

(defn content
  "Returns the `{:text :html}` parts of the email for `draft`, with
   `unsubscribe-url` in the footer of a Product update."
  [{:keys [kind body]} unsubscribe-url]
  {:text (str (legal/render-text body) "\n\n-- \n" (footer-text kind unsubscribe-url))
   :html (html-document kind body unsubscribe-url)})

(defn message
  "Returns the postal message map of `draft` for the recipient `to`. A
   Product update carries the one-click unsubscribe headers of RFC 8058."
  [{:keys [kind subject] :as draft} {:keys [to unsubscribe-url]}]
  (let [{:keys [text html]} (content draft unsubscribe-url)]
    (cond-> {:to      to
             :subject subject
             :body    [:alternative
                       {:type "text/plain; charset=utf-8" :content text}
                       {:type "text/html; charset=utf-8" :content html}]}
      (product-update? kind)
      (assoc "List-Unsubscribe"      (str "<" unsubscribe-url ">")
             "List-Unsubscribe-Post" "List-Unsubscribe=One-Click"))))

;;; Test send

(defn test-unsubscribe-url
  "Returns the unsubscribe link of a test email. Its token is malformed, so
   the link opens the neutral 'not valid' page and unsubscribes nobody."
  []
  (str (conf/base-url) "/unsubscribe/test"))

(defn send-test!
  "Sends `draft` to the operator with a \"[Test] \" subject prefix. Returns
   the address. Throws `:config-error` when no operator address is
   configured, and `:smtp-error` when the relay refuses the email."
  [draft]
  (let [to (or (conf/mail-reply-to)
               (throw (ex-info "No operator address is configured (PARTS__MAIL__REPLY_TO)"
                               {:type :config-error})))]
    (mail/send-personal!
     (update (message draft {:to to :unsubscribe-url (test-unsubscribe-url)})
             :subject #(str "[Test] " %)))
    to))
