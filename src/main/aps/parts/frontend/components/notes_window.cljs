(ns aps.parts.frontend.components.notes-window
  "The Part notes floating window (ADR-0017): the sidebar's notes
   textarea with room to compose. Scope: the selected Part, exactly one —
   otherwise it closes itself. Edits a draft keyed by Part id: Save
   commits (`:map/part-update`), Cancel discards, Close/Escape keep it.
   While it is open the sidebar's quick editor of notes is disabled, so
   two editors never race. Read-only while the canvas is."
  (:require
   [aps.parts.frontend.components.window :refer [window window-actions]]
   [aps.parts.frontend.state.windows :as windows]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect use-ref]]
   [uix.re-frame :as uix.rf]))

(defui notes-window
  []
  (let [part      (windows/scope-part (uix.rf/use-subscribe [:map/selected-parts]))
        editable? (uix.rf/use-subscribe [:canvas/editable?])
        part-id   (:id part)
        saved     (or (:notes part) "")
        draft     (uix.rf/use-subscribe [:ui/window-draft :notes part-id])
        text      (or draft saved)
        dirty?    (and (some? draft) (not= draft saved))
        text-ref  (use-ref nil)
        cancel!   #(rf/dispatch [:window/clear-draft :notes part-id])
        save!     (fn []
                    (rf/dispatch [:map/part-update part-id {:notes text}])
                    (cancel!))]
    (use-effect
     (fn []
       (when-not part
         (rf/dispatch [:window/close :notes])))
     [part])
    ;; Focus the textarea once it exists, caret at the end.
    (use-effect
     (fn []
       (when-let [t @text-ref]
         (let [end (.-length (.-value t))]
           (.focus t)
           (.setSelectionRange t end end))))
     [part-id editable?])
    (when part
      ($ window {:kind  :notes
                 :class "text-window"
                 :title (str "Notes · " (:label part))}
         (if editable?
           ($ :<>
              ($ :textarea {:ref         text-ref
                            :class       "floating-window-text"
                            :aria-label  "Part notes"
                            :value       text
                            :on-change   #(rf/dispatch [:window/set-draft :notes part-id
                                                        (.. % -target -value)])
                            :on-key-down (fn [^js e]
                                           (when (and dirty?
                                                      (= "Enter" (.-key e))
                                                      (or (.-metaKey e) (.-ctrlKey e)))
                                             (save!)))})
              ($ window-actions {:dirty?    dirty?
                                 :on-save   save!
                                 :on-cancel cancel!}))
           (if (seq saved)
             ($ :p {:class "text-sm whitespace-pre-wrap"} saved)
             ($ :p {:class "text-sm text-base-content/50 italic"} "No notes")))))))
