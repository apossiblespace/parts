(ns aps.parts.frontend.components.modal
  (:require
   [uix.core :refer [defui $ use-ref use-effect]]))

(defui modal
  [{:keys [show title on-close children]}]
  (let [dialog-ref (use-ref nil)]

    ;; control modal visibility based on show prop
    (use-effect
     (fn []
       (when-let [dialog @dialog-ref]
         (if show
           (.showModal dialog)
           (.close dialog))))
     [show])

    ($ :dialog
       {:class "modal"
        :ref dialog-ref
        :on-click (fn [e]
                    ;; close when clicking backdrop
                    (when (= (.-target e) @dialog-ref)
                      (on-close)))
        :on-cancel on-close} ;; handles ESC key
       ($ :div
          {:class "modal-box"
           :on-click #(.stopPropagation %)}
          ($ :button
             {:class "btn btn-sm btn-circle btn-ghost absolute right-2 top-2"
              :on-click on-close}
             "✕")
          (when title
            ($ :h3 {:class "text-lg font-bold mb-4"} title))
          children))))
