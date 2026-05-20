(ns aps.parts.frontend.components.auth-screen
  "Full-page login screen shown by the SPA root when the user is not yet
   authenticated. The URL does not change — once login succeeds the root
   switch re-renders the router, which is still matching the original
   deep-linked URL, so the user lands exactly where they were heading."
  (:require
   [aps.parts.frontend.components.login-form :refer [login-form]]
   [uix.core :refer [defui $]]))

(defui auth-screen []
  ;; `login-form` needs no `on-success` here: a successful login dispatches
  ;; `:auth/check-auth` in `:auth/login-fx`, which refreshes `:auth/user`
  ;; and flips the SPA root from this screen to the router — still matching
  ;; the original deep-linked URL.
  ($ :div {:class "min-h-screen flex items-center justify-center bg-gray-50 p-4"}
     ($ :div {:class "card w-full max-w-sm bg-white shadow-sm border border-base-300"}
        ($ :div {:class "card-body"}
           ($ :a {:href "/" :class "flex justify-center mb-4"}
              ($ :img {:class "w-40" :src "/images/parts-logo-horizontal.svg"}))
           ($ :h1 {:class "text-lg font-bold text-center mb-4"} "Log in")
           ($ login-form {})))))
