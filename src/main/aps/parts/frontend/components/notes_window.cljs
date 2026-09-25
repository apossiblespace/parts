(ns aps.parts.frontend.components.notes-window
  "The notes floating window (ADR-0017): the sidebar's notes textarea
   with room to compose. Scope: `windows/scope-notes` — one Part, or one
   Relationship — otherwise it closes itself. Edits a draft keyed by the
   entity's id: Save commits, Cancel discards, Close/Escape keep it —
   until the notes change in the sidebar, which drops it.
   While it is open the sidebar's quick editor of notes is disabled, so
   two editors never race. Read-only while the canvas is."
  (:require
   [aps.parts.common.constants :refer [max-text-length]]
   [aps.parts.frontend.components.window :refer [window window-actions]]
   [aps.parts.frontend.state.windows :as windows]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect use-ref]]
   [uix.re-frame :as uix.rf]))

(defui notes-window
  []
  (let [[kind entity] (windows/scope-notes (uix.rf/use-subscribe [:map/selected-parts])
                                           (uix.rf/use-subscribe [:map/selected-relationships]))
        names         (uix.rf/use-subscribe [:canvas/part-names])
        editable?     (uix.rf/use-subscribe [:canvas/editable?])
        entity-id     (:id entity)
        saved         (or (:notes entity) "")
        stored        (uix.rf/use-subscribe [:ui/window-draft :notes entity-id])
        ;; A draft started from older notes (changed in the sidebar since) is stale.
        draft         (when (= (:base stored) saved) (:text stored))
        text          (or draft saved)
        dirty?        (and (some? draft) (not= draft saved))
        text-ref      (use-ref nil)
        cancel!       #(rf/dispatch [:window/clear-draft :notes entity-id])
        save!         (fn []
                        (rf/dispatch [(if (= kind :part) :map/part-update :map/relationship-update)
                                      entity-id {:notes text}])
                        (cancel!))]
    (use-effect
     (fn []
       (when-not entity
         (rf/dispatch [:window/close :notes])))
     [entity])
    ;; Delete a stale draft, or it returns when the notes (often "") go
    ;; back to the value it started from.
    (use-effect
     (fn []
       (when (and stored (nil? draft))
         (rf/dispatch [:window/clear-draft :notes entity-id])))
     [stored draft entity-id])
    ;; Focus the textarea once it exists, caret at the end.
    (use-effect
     (fn []
       (when-let [t @text-ref]
         (let [end (.-length (.-value t))]
           (.focus t)
           (.setSelectionRange t end end))))
     [entity-id editable?])
    (when entity
      ($ window {:kind  :notes
                 :class "text-window"
                 :title (str "Notes: " (if (= kind :part)
                                         (:label entity)
                                         (str (:label (names (:source_id entity))) " → "
                                              (:label (names (:target_id entity))))))}
         (if editable?
           ($ :<>
              ($ :textarea {:max-length  max-text-length
                            :ref         text-ref
                            :class       "floating-window-text"
                            :aria-label  "Notes"
                            :value       text
                            :on-change   #(rf/dispatch [:window/set-draft :notes entity-id
                                                        {:base saved :text (.. % -target -value)}])
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
