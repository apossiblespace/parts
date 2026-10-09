(ns aps.parts.legal-test
  (:require
   [aps.parts.legal :as legal]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest test-parse-front-matter
  (let [parse #'legal/parse]
    (testing "extracts version and strips the front-matter block"
      (let [{:keys [version body]} (parse "---\nversion: 2026-06-01\n---\n# Title\n\nBody.")]
        (is (= "2026-06-01" version))
        (is (= "# Title\n\nBody." body))))
    (testing "no front matter -> nil version, body unchanged"
      (let [{:keys [version body]} (parse "# Title\n\nBody.")]
        (is (nil? version))
        (is (= "# Title\n\nBody." body))))))

(deftest render-html-strips-dangerous-content
  (testing "script, event handlers, and javascript: URLs are removed; safe structure survives"
    (let [html (legal/render-html
                (str "<script>alert(1)</script>\n\n"
                     "# Title\n\n"
                     "[x](javascript:alert(2))\n\n"
                     "<img src=x onerror=alert(3)>"))]
      (is (not (str/includes? html "<script")))
      (is (not (str/includes? html "onerror")))
      (is (not (str/includes? html "javascript:")))
      (is (str/includes? html "Title")))))

(deftest test-render-text
  (testing "renders headings and paragraphs as plain lines and writes links as text (url)"
    (is (= "Hello world!\n\nThis is a test of the notification (https://gosha.net)"
           (legal/render-text "# Hello world!\n\nThis is a test of the [notification](https://gosha.net)"))))
  (testing "writes a link whose text is the url once"
    (is (= "See https://ifs.tools" (legal/render-text "See <https://ifs.tools>"))))
  (testing "drops emphasis markers and decodes entities"
    (is (= "Bold & italic" (legal/render-text "**Bold** &amp; *italic*"))))
  (testing "renders bullet and numbered lists"
    (is (= "Intro\n\n- one\n- two\n\nNext\n\n1. first\n2. second"
           (legal/render-text "Intro\n\n- one\n- two\n\nNext\n\n1. first\n2. second"))))
  (testing "drops script, like the html rendering"
    (is (= "Hi" (legal/render-text "Hi\n\n<script>alert(1)</script>"))))
  (testing "indents a nested list under its item"
    (is (= "- a\n  - b\n  - c\n- d" (legal/render-text "- a\n    - b\n    - c\n- d"))))
  (testing "starts the line after a hard break without a space"
    (is (= "a\nb" (legal/render-text "a  \nb"))))
  (testing "marks quoted lines"
    (is (= "> one\n>\n> two\n\nafter" (legal/render-text "> one\n>\n> two\n\nafter"))))
  (testing "writes a mailto address once"
    (is (= "Text a@b.com" (legal/render-text "Text <a@b.com>"))))
  (testing "keeps the line breaks and indentation of code"
    (is (= "Intro\n\ncode\n\n\n\n  more\n\nAfter"
           (legal/render-text "Intro\n\n```\ncode\n\n\n\n  more\n```\n\nAfter")))))
