(ns aps.parts.frontend.components.delete-confirmation-modal
  (:require
   [aps.parts.frontend.components.modal :refer [modal]]
   [uix.core :refer [defui $ use-effect use-ref]]))

(defui delete-confirmation-modal
  "A confirm/cancel modal for destructive deletions. Enter confirms regardless
   of which button currently has focus; Escape (and backdrop click) cancel via
   the underlying <dialog>."
  [{:keys [show title body confirm-label on-confirm on-close]}]
  (let [confirm-ref (use-ref nil)]

    ;; Pull focus onto Confirm whenever the modal opens, so Enter on a freshly
    ;; opened modal always confirms.
    (use-effect
     (fn []
       (when (and show @confirm-ref)
         (.focus @confirm-ref)))
     [show])

    ;; Global Enter handler — covers the case where the user has tabbed focus
    ;; onto Cancel but still expects Enter to confirm (per requested behaviour).
    (use-effect
     (fn []
       (when show
         (let [handler (fn [^js e]
                         (when (= "Enter" (.-key e))
                           (.preventDefault e)
                           (on-confirm)))]
           (.addEventListener js/document "keydown" handler)
           (fn [] (.removeEventListener js/document "keydown" handler)))))
     [show on-confirm])

    ($ modal
       {:show     show
        :title    title
        :on-close on-close}
       ($ :<>
          ($ :p {:class "text-xs text-gray-700 mb-6"} body)
          ($ :div {:class "modal-action space-x-2 flex"}
             ($ :button
                {:type     "button"
                 :class    "btn btn-sm flex-1"
                 :on-click on-close}
                "Cancel")
             ($ :button
                {:ref      confirm-ref
                 :type     "button"
                 :class    "btn btn-sm btn-error flex-1"
                 :on-click on-confirm}
                (or confirm-label "Delete")))))))
