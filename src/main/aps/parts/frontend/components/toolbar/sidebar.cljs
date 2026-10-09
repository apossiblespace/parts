(ns aps.parts.frontend.components.toolbar.sidebar
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.components.toolbar.parts-tools :refer [parts-tools]]
   [aps.parts.frontend.components.toolbar.relationships-tools :refer [relationships-tools]]
   [aps.parts.frontend.components.toolbar.session-card :refer [session-card]]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as uix.rf]))

(defui ^:memo sidebar
  "Map-canvas sidebar: Part / Relationship tool palettes, plus the
   demo-mode sign-up / log-in CTAs for unauthenticated playground
   visitors. Auth status (logged-in user + log-out action) is no longer
   rendered here — it moved to the Maps-list page header.

   `on-delete-part` / `on-delete-relationship` (each fn [id]) forward to
   the forms' delete buttons — props, not re-frame, because the delete
   confirmation state lives in the map canvas alongside the Delete-key
   flow it shares."
  [{:keys [on-delete-part on-delete-relationship]}]
  (let [demo             (uix.rf/use-subscribe [:demo])
        minimal          (uix.rf/use-subscribe [:minimal-demo])
        selected-parts   (uix.rf/use-subscribe [:map/selected-parts])
        selected-rels    (uix.rf/use-subscribe [:map/selected-relationships])
        the-sessions     (uix.rf/use-subscribe [:map/sessions])
        has-demo-cta     (and demo (not minimal))
        has-selection    (or (seq selected-parts) (seq selected-rels))
        ;; The Session card is a permanent fixture once Sessions are
        ;; loaded — the sidebar is no longer selection-only.
        has-session-card (some? the-sessions)]
    (when (or has-demo-cta has-selection has-session-card)
      ;; dvh, not vh: on iOS Safari 100vh overshoots the visible viewport
      ;; (see .map-view in main.css), which would let the sidebar run
      ;; behind the browser chrome.
      ($ :div {:class "sidebar max-h-[calc(100dvh-200px)] flex flex-col rounded-sm border-base-300 border bg-base-100 shadow-sm"}
         (when has-demo-cta
           ($ :div {:class "p-2 space-y-2"}
              ($ :a
                 {:class    "btn btn-sm btn-primary w-full"
                  :href     "/app/signup"
                  :on-click #(o/track "Create Account Click" {:source "playground"})}
                 "Create an account")
              ($ :a
                 {:class    "btn btn-sm btn-ghost w-full"
                  :href     "/app/login"
                  :on-click #(o/track "Login Click" {:source "playground"})}
                 "Log in")))
         ($ :div {:class "overflow-auto"}
            ($ session-card)
            ($ parts-tools {:on-delete on-delete-part})
            ($ relationships-tools {:on-delete on-delete-relationship}))))))
