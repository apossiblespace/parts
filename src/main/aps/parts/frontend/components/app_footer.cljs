(ns aps.parts.frontend.components.app-footer
  "Shared footer for the signed-in pages (Maps list, Account): a support
   prompt, copyright, the legal-document links, source/docs links, and the
   build version."
  (:require
   [aps.parts.common.constants :as c]
   [uix.core :refer [$ defui]]))

(defn- app-version
  "Read the app version from the `<meta name=\"version\">` tag stamped on
   first page load by the server's shared head (see
   `aps.parts.views.partials/head`). Same channel `data-launched` uses to
   flow build-time facts from server to client. Returns nil if the tag
   isn't present (e.g. in tests that bypass the server shell)."
  []
  (some-> (.querySelector js/document "meta[name=\"version\"]")
          (.getAttribute "content")))

(defui app-footer []
  ;; `mt-auto` makes the footer stick to the bottom of the flex-column page
  ;; when content is short, and fall back to natural flow (no extra top gap)
  ;; when content fills the screen. The pages must be `flex flex-col` with a
  ;; growing content wrapper for this to take effect.
  ($ :footer {:class "mt-auto mb-6 pt-6 border-t border-gray-200 flex flex-col gap-3 text-xs text-gray-500"}
     ($ :div {:class "flex items-center justify-between gap-3"}
        ($ :div {:class "flex items-center gap-1"}
           ($ :span {:class "text-black font-bold"} "🆘 Need help?")
           ($ :span " Email us for a quick reply: ")
           ($ :a {:href (str "mailto:" c/support-email) :class "hover:text-ifs-green"}
              c/support-email)))
     ($ :div {:class "flex items-center gap-3 text-gray-400"}
        ($ :span
           "© 2026 "
           ($ :a {:href   "https://a.possible.space/"
                  :target "_blank"}
              "A Possible Space Ltd."))
        (for [{:keys [slug mini-label]} c/legal-documents]
          ($ :a {:key   slug
                 :href  (str "/" slug)
                 :class "hover:text-ifs-green"}
             mini-label))
        ($ :a {:href "https://github.com/apossiblespace/parts"} "Source code")
        ($ :a {:href "https://github.com/apossiblespace/parts?tab=readme-ov-file#readme"} "Docs")
        (when-let [v (app-version)]
          ($ :div {:class "text-gray-400"}
             "Version: "
             ($ :span {:class "font-mono"} v))))))
