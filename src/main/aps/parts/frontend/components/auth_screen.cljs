(ns aps.parts.frontend.components.auth-screen
  "Full-page auth screen for the /app/login and /app/signup routes — also
   shown gate-in-place over a protected route when the user is not yet
   authenticated (the URL stays put; once auth succeeds the SPA root
   re-renders the router on the route already in the URL).

   `mode` is :login or :signup, decided by the SPA root from the current
   route. Signup is only routed to when `launched?` is true; the login
   view offers a 'Create an account' cross-link only when launched."
  (:require
   [aps.parts.frontend.components.login-form :refer [login-form]]
   [aps.parts.frontend.components.signup-form :refer [signup-form]]
   [uix.core :refer [defui $]]
   [uix.re-frame :as uix.rf]))

(defui auth-screen [{:keys [mode]}]
  (let [launched (uix.rf/use-subscribe [:launched])
        signup?  (= mode :signup)]
    ($ :div {:class "min-h-screen flex items-center justify-center bg-gray-50 p-4"}
       ($ :div {:class "card w-full max-w-sm bg-white shadow-sm border border-base-300"}
          ($ :div {:class "card-body"}
             ($ :a {:href "/" :class "flex justify-center mb-4"}
                ($ :img {:class "w-40" :src "/images/parts-logo-horizontal.svg"}))
             ($ :h1 {:class "text-lg font-bold text-center mb-4"}
                (if signup? "Create an account" "Log in"))
             (if signup?
               ($ signup-form {})
               ($ login-form {}))
             ;; Cross-link to the other auth route. reitit-frontend turns
             ;; same-origin anchor clicks into client-side navigation.
             ;; Pre-launch the login view shows no link — there is nowhere
             ;; to go, signup being invite-only.
             (cond
               signup?
               ($ :p {:class "text-sm text-center mt-4"}
                  "Already have an account? "
                  ($ :a {:href "/app/login"}
                     "Log in"))

               launched
               ($ :p {:class "text-sm text-center mt-4"}
                  "Don't have an account? "
                  ($ :a {:href "/app/signup"}
                     "Create one"))

               :else nil))))))
