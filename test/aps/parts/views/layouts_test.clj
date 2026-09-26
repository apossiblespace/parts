(ns aps.parts.views.layouts-test
  "Which layouts load the app bundle. Pages with no #root and no htmx forms
   must not make slow devices download and parse it (TASK-122)."
  (:require
   [aps.parts.views.layouts :as layouts]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- loads-main-js? [html]
  (str/includes? (str html) "src=\"/js/main.js\""))

(deftest main-js-per-layout
  (testing "the app shell and the marketing page load the bundle"
    (is (loads-main-js? (layouts/fullscreen {} "body")))
    (is (loads-main-js? (layouts/marketing {} "body"))))

  (testing "the legal, invite and password-reset layouts do not"
    (is (not (loads-main-js? (layouts/document {} "body"))))
    (is (not (loads-main-js? (layouts/content-page "Title" "body"))))))
