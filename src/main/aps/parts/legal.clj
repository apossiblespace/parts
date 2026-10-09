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
   [clojure.string :as str]
   [markdown.core :as md])
  (:import
   (java.io File)
   (org.owasp.html HtmlSanitizer HtmlStreamEventReceiver PolicyFactory Sanitizers)))

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

;;; Plain text

(def ^:private block-tags
  #{"p" "div" "blockquote" "ul" "ol" "h1" "h2" "h3" "h4" "h5" "h6"})

(defn- line-start?
  [^StringBuilder out]
  (or (zero? (.length out)) (= \newline (.charAt out (dec (.length out))))))

(defn- break!
  "Ends the text in `out` with at least `n` line breaks. Does nothing at
   the start of the text."
  [^StringBuilder out n]
  (while (and (pos? (.length out))
              (#{\space \tab} (.charAt out (dec (.length out)))))
    (.setLength out (dec (.length out))))
  (when (pos? (.length out))
    (let [found (loop [i (dec (.length out)) found 0]
                  (if (and (>= i 0) (= \newline (.charAt out i)))
                    (recur (dec i) (inc found))
                    found))]
      (dotimes [_ (- n found)] (.append out "\n")))))

(defn- quote-lines
  "Returns `text` with each line marked as quoted."
  [text]
  (->> (str/split-lines (str/trimr text))
       (map #(if (str/blank? %) ">" (str "> " %)))
       (str/join "\n")))

(defn- attribute [attrs attr-name]
  (some (fn [[k v]] (when (= k attr-name) v)) (partition 2 attrs)))

(defn render-text
  "Renders a Markdown string to plain text, through the same allowlist as
   `render-html`, so both renderings carry the same content. A link is
   written as \"text (url)\", or as the bare URL when the text is the URL.
   Code keeps its line breaks and indentation."
  [markdown]
  (let [out        (StringBuilder.)
        links      (atom ())
        lists      (atom ())
        quotes     (atom ())
        code-depth (atom 0)
        marker-end (atom nil)
        list-break (fn [] (break! out (if (seq @lists) 1 2)))]
    (HtmlSanitizer/sanitize
     (md/md-to-html-string markdown)
     (.apply html-policy
             (reify HtmlStreamEventReceiver
               (openDocument [_])
               (closeDocument [_])
               (openTag [_ tag attrs]
                 (cond
                   (#{"ul" "ol"} tag) (do (list-break)
                                          (swap! lists conj (when (= tag "ol") 0)))
                   ;; A paragraph in a list item starts on the marker line.
                   (block-tags tag)   (do (when-not (= (.length out) @marker-end)
                                            (break! out 2))
                                          (when (= tag "blockquote")
                                            (swap! quotes conj (.length out))))
                   (= tag "li")       (let [n      (first @lists)
                                            indent (apply str (repeat (* 2 (dec (count @lists))) " "))]
                                        (break! out 1)
                                        (when n (swap! lists #(conj (rest %) (inc n))))
                                        (.append out (str indent (if n (str (inc n) ". ") "- ")))
                                        (reset! marker-end (.length out)))
                   (= tag "br")       (.append out "\n")
                   (= tag "code")     (swap! code-depth inc)
                   (= tag "a")        (swap! links conj [(attribute attrs "href") (.length out)])))
               (closeTag [_ tag]
                 (cond
                   (#{"ul" "ol"} tag)    (do (swap! lists rest) (list-break))
                   (= tag "blockquote")  (let [start (first @quotes)
                                               text  (subs (str out) start)]
                                           (swap! quotes rest)
                                           (.setLength out start)
                                           (.append out (quote-lines text))
                                           (break! out 2))
                   (block-tags tag)      (break! out 2)
                   (= tag "code")        (swap! code-depth dec)
                   (= tag "a")           (let [[href start] (first @links)
                                               text         (str/trim (subs (str out) start))]
                                           (swap! links rest)
                                           (when (and href (not (#{text (str "mailto:" text)} href)))
                                             (.append out (str " (" href ")"))))))
               ;; Outside code, whitespace between blocks is dropped, and a
               ;; new line does not start with the space that markdown-clj
               ;; writes after a line break.
               (text [_ text]
                 (cond
                   (pos? @code-depth) (.append out text)
                   (line-start? out)  (.append out (str/triml text))
                   :else              (.append out text))))))
    (str/trim (str out))))

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
   fallback, so a fresh self-host has no PDF and the download link is hidden.
   The slug is checked against the document allowlist here, not only at the
   call sites, so no future caller can turn it into a path probe."
  [slug]
  (when (contains? documents slug)
    (let [dir  (conf/legal-content-dir)
          file (when dir (io/file dir (str slug ".pdf")))]
      (when (and file (.exists ^File file)) file))))

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
