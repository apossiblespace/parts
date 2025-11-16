(ns aps.parts.frontend.components.waitlist-modal
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.api.utils :as utils]
   [aps.parts.frontend.components.modal :refer [modal]]
   [uix.core :refer [defui $ use-state]]))

(defui waitlist-modal [{:keys [show on-close]}]
  (let [[email set-email]                         (use-state "")
        [loading set-loading]                     (use-state false)
        [success set-success]                     (use-state false)
        [already-signed-up set-already-signed-up] (use-state false)
        [error set-error]                         (use-state nil)

        handle-close                              (fn []
                                                    (on-close)
                                                    (set-loading false)
                                                    (set-success false)
                                                    (set-already-signed-up false)
                                                    (set-error nil)
                                                    (set-email ""))

        handle-submit                             (fn [e]
                                                    (.preventDefault e)
                                                    (when-not (or loading success already-signed-up)
                                                      (o/track "Waitlist Signup" {:source "playground"})
                                                      (set-loading true)
                                                      (set-error nil)
                                                      (let [csrf-token (utils/get-csrf-token)
                                                            body-data  (cond-> (str "email=" (js/encodeURIComponent email))
                                                                         csrf-token (str "&__anti-forgery-token=" (js/encodeURIComponent csrf-token)))]
                                                        (-> (js/fetch "/waitlist-signup"
                                                                      #js {:method  "POST"
                                                                           :headers #js {"Content-Type" "application/x-www-form-urlencoded"}
                                                                           :body    body-data})
                                                            (.then (fn [response]
                                                                     (-> (.text response)
                                                                         (.then (fn [html]
                                                                                  (set-loading false)
                                                                                  (cond
                                                                                    (= 201 (.-status response))
                                                                                    (set-success true)

                                                                                    (= 409 (.-status response))
                                                                                    (set-already-signed-up true)

                                                                                    (= 400 (.-status response))
                                                                                    (cond
                                                                                      (re-find #"don't.+forget.+email" html)
                                                                                      (set-error "Please enter your email address")

                                                                                      (re-find #"not.+valid.+email" html)
                                                                                      (set-error "Please enter a valid email address")

                                                                                      :else
                                                                                      (set-error "Please check your email address"))

                                                                                    :else
                                                                                    (set-error "Something went wrong. Please try again.")))))))
                                                            (.catch (fn [_]
                                                                      (set-loading false)
                                                                      (set-error "Something went wrong. Please try again.")))))))]

    ($ modal
       {:show     show
        :on-close handle-close
        :title    "Join the Founding Practitioners Circle"}

       ($ :<>
          (cond
            success
            ($ :div {:class "text-center py-6"}
               ($ :div {:class "text-6xl mb-4"} "🎉")
               ($ :h3 {:class "text-xl font-semibold mb-2"} "Welcome to the Founding Practitioners Circle!")
               ($ :p {:class "text-gray-600 mb-6"} "You'll be among the first to get early access to Parts.")
               ($ :button
                  {:type     "button"
                   :class    "btn btn-primary"
                   :on-click handle-close}
                  "Got it!"))

            already-signed-up
            ($ :div {:class "text-center py-6"}
               ($ :div {:class "text-6xl mb-4"} "👋")
               ($ :h3 {:class "text-xl font-semibold mb-2"} "You're already in the circle!")
               ($ :p {:class "text-gray-600 mb-6"} "We have your email and you'll be among the first to get early access to Parts.")
               ($ :button
                  {:type     "button"
                   :class    "btn btn-primary"
                   :on-click handle-close}
                  "Got it!"))

            :else
            ($ :<>
               ;; Benefits section
               ($ :div {:class "mb-6"}
                  ($ :div {:class "space-y-4"}
                     ($ :div {:class "flex items-start space-x-3"}
                        ($ :img {:src   "/images/icons/build.png"
                                 :alt   "An icon representing a toolbox with some tools in it"
                                 :class "w-8 h-8 mt-0.5 flex-shrink-0"})
                        ($ :div
                           ($ :h4 {:class "font-medium text-gray-800"} "Help shape Parts")
                           ($ :p {:class "text-sm text-gray-600"} "Your feedback will make Parts better for clients & therapists")))
                     ($ :div {:class "flex items-start space-x-3"}
                        ($ :img {:src   "/images/icons/key.png"
                                 :alt   "An icon representing a key on a keychain"
                                 :class "w-8 h-8 mt-0.5 flex-shrink-0"})
                        ($ :div
                           ($ :h4 {:class "font-medium text-gray-800"} "Early access")
                           ($ :p {:class "text-sm text-gray-600"} "Start using Parts and new features before general availability")))
                     ($ :div {:class "flex items-start space-x-3"}
                        ($ :img {:src   "/images/icons/concierge.png"
                                 :alt   "An icon representing a concierge's bell"
                                 :class "w-8 h-8 mt-0.5 flex-shrink-0"})
                        ($ :div
                           ($ :h4 {:class "font-medium text-gray-800"} "Concierge support")
                           ($ :p {:class "text-sm text-gray-600"} "Help getting setup and started, straight from the developer")))))

               (when error
                 ($ :div {:class "alert alert-error mb-4"}
                    ($ :span {:class "font-medium"} error)))

               ($ :form {:on-submit handle-submit}

                  ($ :div {:class "form-control mb-6"}
                     ($ :label {:class "fieldset-label mb-2" :for "email"}
                        "Your email address")
                     ($ :input
                        {:type        "email"
                         :id          "email"
                         :placeholder "self@you.com"
                         :class       "input w-full"
                         :value       email
                         :disabled    loading
                         :on-change   #(set-email (.. % -target -value))
                         :on-focus    #(o/track "Email Field Focus" {:source "playground"})
                         :required    true}))

                  ($ :div {:class "modal-action space-x-2 flex"}
                     ($ :button
                        {:type     "button"
                         :class    "btn flex-1"
                         :disabled loading
                         :on-click on-close}
                        "Cancel")
                     ($ :button
                        {:type     "submit"
                         :disabled loading
                         :class    "btn btn-primary flex-1"}
                        (if loading
                          ($ :<>
                             ($ :span {:class "loading loading-spinner"})
                             "Signing up...")
                          "Sign me up!"))))))))))
