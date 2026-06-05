(ns aps.parts.analytics-test
  "Where the Plausible collector is allowed to load. It must appear on the
   public marketing surfaces and never on the signed-in app or the invite
   pages, whose URLs carry a Map id or an invite token (task-048)."
  (:require
   [aps.parts.handlers.pages :as pages]
   [aps.parts.views.layouts :as layouts]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- loads-plausible?
  "True when HTML loads the Plausible *collector* — the remote analytics
   script that sends data to plausible.io.

   Careful: the inline `window.plausible` stub and the `plausible(...)` onclick
   handlers are present regardless of whether analytics is on, so a bare search
   for \"plausible\" is always true. This must detect the remote collector
   specifically."
  [html]
  (str/includes? html "plausible.io/js/script"))

(defn- page-html
  "Render a page handler (which ignores its request) to its HTML body string."
  [handler]
  (str (:body (handler {}))))

(deftest collector-suppressed-on-app-and-invite
  (testing "the signed-in app shell does not load the collector — no Map id leaks"
    (is (not (loads-plausible? (page-html pages/app-shell)))))
  (testing "fullscreen without :analytics? omits it — this is what the invite pages use"
    (is (not (loads-plausible? (str (layouts/fullscreen {} "body")))))))

(deftest collector-present-on-public-surfaces
  (testing "the playground loads it — the demo funnel is preserved"
    (is (loads-plausible? (page-html pages/playground))))
  (testing "the marketing and document layouts load it"
    (is (loads-plausible? (str (layouts/marketing {} "body"))))
    (is (loads-plausible? (str (layouts/document {} "body")))))
  (testing "fullscreen opts in explicitly with :analytics? true"
    (is (loads-plausible? (str (layouts/fullscreen {:analytics? true} "body"))))))
