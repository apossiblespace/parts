(ns aps.parts.frontend.components.auth-screen
  "Full-page auth screen for the /app/login and /app/signup routes — also
   shown gate-in-place over a protected route when the user is not yet
   authenticated (the URL stays put; once auth succeeds the SPA root
   re-renders the router on the route already in the URL).

   `mode` is :login or :signup, decided by the SPA root from the current
   route. Each view links to the other."
  (:require
   [aps.parts.frontend.components.login-form :refer [login-form]]
   [aps.parts.frontend.components.signup-form :refer [signup-form]]
   [uix.core :refer [defui $]]))

(defui auth-screen [{:keys [mode]}]
  (let [signup? (= mode :signup)]
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
             (if signup?
               ($ :p {:class "text-sm text-center mt-4"}
                  "Already have an account? "
                  ($ :a {:href "/app/login"}
                     "Log in"))
               ($ :p {:class "text-sm text-center mt-4"}
                  "Don't have an account? "
                  ($ :a {:href "/app/signup"}
                     "Create one"))))))))
