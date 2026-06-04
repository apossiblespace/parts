(ns aps.parts.legal
  "Loads and renders the operator's legal documents (Privacy Policy, Terms of
   Service, Data Processing Agreement).

   The documents are operator *content*, not committed to this open-source
   repo. At runtime each is read from the directory configured at
   `:legal/content-dir`; if that is unset or the file is missing, the bundled
   example template under `resources/legal/<slug>.example.md` is used instead.

   Rendered HTML is sanitised to a small allowlist: markdown-clj passes raw
   HTML and script through, so the output is filtered before it reaches a page."
  (:require
   [aps.parts.common.constants :as c]
   [aps.parts.config :as conf]
   [clojure.java.io :as io]
   [markdown.core :as md])
  (:import
   (java.io File)
   (org.owasp.html PolicyFactory Sanitizers)))

(def documents
  "Slug -> display title, from the shared legal-documents list."
  (into {} (map (juxt :slug :label)) c/legal-documents))

(def ^:private ^PolicyFactory html-policy
  "Allowlist for rendered legal Markdown: structural blocks, inline formatting,
   and safe links only. Everything else — script, img, event handlers,
   javascript: URLs — is stripped, so a compromised or mistaken parts-ops
   cannot inject active content on this origin."
  (.and (.and Sanitizers/BLOCKS Sanitizers/FORMATTING) Sanitizers/LINKS))

(defn render-html
  "Render a Markdown string to sanitised HTML (allowlist; raw HTML / script
   removed)."
  [markdown]
  (.sanitize html-policy (md/md-to-html-string markdown)))

(defn- source
  "Raw Markdown for `slug`: the operator's file under `:legal/content-dir` if it
   exists, else the bundled example template, else nil."
  [slug]
  (let [dir  (conf/legal-content-dir)
        file (when dir (io/file dir (str slug ".md")))]
    (if (and file (.exists ^File file))
      (slurp file)
      (some-> (io/resource (str "legal/" slug ".example.md")) slurp))))

(defn- parse
  "Split an optional leading `--- ... ---` front-matter block off the Markdown
   body. Returns {:version <string-or-nil> :body <markdown-string>}. Only the
   `version:` field is read from the front matter."
  [raw]
  (if-let [[_ front-matter body]
           (re-matches #"(?s)---[ \t]*\n(.*?)\n---[ \t]*\n?(.*)" raw)]
    {:version (some-> (re-find #"(?m)^version:[ \t]*(.+?)[ \t]*$" front-matter)
                      second)
     :body    body}
    {:version nil :body raw}))

(defn pdf-file
  "java.io.File for the operator's `<slug>.pdf` in the content dir if present,
   else nil. PDFs are operator artifacts only — there is no bundled-example
   fallback, so a fresh self-host has no PDF and the download link is hidden."
  [slug]
  (let [dir  (conf/legal-content-dir)
        file (when dir (io/file dir (str slug ".pdf")))]
    (when (and file (.exists ^File file)) file)))

(defn document
  "The loaded legal document for `slug`, or nil if the slug is unknown or no
   source exists. Returns {:slug :title :version :markdown :html}."
  [slug]
  (when (contains? documents slug)
    (when-let [raw (source slug)]
      (let [{:keys [version body]} (parse raw)]
        {:slug     slug
         :title    (get documents slug)
         :version  version
         :markdown body
         :html     (render-html body)}))))
