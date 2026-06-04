(ns aps.parts.handlers.legal
  "Server-rendered legal-document pages (/privacy, /terms, /dpa) and their PDF
   downloads. Content comes from `aps.parts.legal`, which reads the operator's
   files at runtime and falls back to bundled example templates. Pages render
   in the `document` layout and are auth-agnostic."
  (:require
   [aps.parts.legal :as legal]
   [aps.parts.views.layouts :as layouts]
   [hiccup.util :as hu]
   [hiccup2.core :refer [html]]
   [ring.util.response :as response]))

(defn- not-published-page
  [slug]
  (html
   (layouts/document {:title "Not published" :styles ["/css/style.css"] :active slug}
                     [:h1 {:class "text-2xl font-bold mb-4"} "Not published"]
                     [:p {:class "text-gray-600"}
                      "This instance has not published this document."])))

(defn- document-page
  [doc]
  (let [pdf? (some? (legal/pdf-file (:slug doc)))]
    (html
     (layouts/document {:title (:title doc) :styles ["/css/style.css"] :active (:slug doc)}
                       [:div.legal-doc (hu/raw-string (:html doc))]
                       [:p {:class "mt-10 pt-6 border-t border-gray-200 text-sm text-gray-500"}
                        (when (:version doc) (str "Version " (:version doc)))
                        (when (and (:version doc) pdf?) " · ")
                        (when pdf?
                          [:a {:href (str "/" (:slug doc) "/download")}
                           "Download PDF"])]))))

(defn page
  "Ring handler for GET /<slug> — the rendered legal document in the document
   layout, or a 404 'not published' page when no source exists."
  [slug]
  (fn [_request]
    (if-let [doc (legal/document slug)]
      (response/response (document-page doc))
      (-> (response/response (not-published-page slug))
          (response/status 404)))))

(defn download
  "Ring handler for GET /<slug>/download — the operator's `<slug>.pdf` as a file
   attachment, or 404 when no PDF is present."
  [slug]
  (fn [_request]
    (if-let [f (legal/pdf-file slug)]
      (-> (response/response f)
          (response/content-type "application/pdf")
          (response/header "Content-Disposition"
                           (str "attachment; filename=\"" slug ".pdf\"")))
      (-> (response/not-found "No PDF available")
          (response/content-type "text/plain; charset=utf-8")))))
