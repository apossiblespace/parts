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
