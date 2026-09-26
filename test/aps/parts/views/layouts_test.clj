(ns aps.parts.views.layouts-test
  "Which layouts load the app bundle, and how asset URLs are versioned.
   Pages with no #root and no htmx forms must not make slow devices
   download and parse the bundle (TASK-122)."
  (:require
   [aps.parts.config :as conf]
   [aps.parts.views.layouts :as layouts]
   [aps.parts.views.partials :as partials]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- loads-main-js? [html]
  (str/includes? (str html) "src=\"/js/main.js"))

(deftest main-js-per-layout
  (testing "the app shell and the marketing page load the bundle"
    (is (loads-main-js? (layouts/fullscreen {} "body")))
    (is (loads-main-js? (layouts/marketing {} "body"))))

  (testing "the legal, invite and password-reset layouts do not"
    (is (not (loads-main-js? (layouts/document {} "body"))))
    (is (not (loads-main-js? (layouts/content-page "Title" "body"))))))

(deftest asset-url-test
  (testing "a local file gets a version from its content"
    (is (re-matches #"/marketing\.js\?v=[0-9a-f]{8}" (partials/asset-url "/marketing.js"))))

  (testing "a path with no file is left unchanged"
    (is (= "/js/no-such-file.js" (partials/asset-url "/js/no-such-file.js"))))

  (testing "dev renders plain paths, so hot reload keeps working"
    (with-redefs [conf/dev? (constantly true)]
      (is (= "/marketing.js" (partials/asset-url "/marketing.js"))))))
