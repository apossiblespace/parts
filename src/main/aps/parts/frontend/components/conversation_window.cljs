(ns aps.parts.frontend.components.conversation-window
  "The conversation floating window (ADR-0017, ADR-0018).

   Two modes over the same entries (`conversations/window-mode`):
   - Part mode — the selected Part's conversation across Sessions, with a
     composer that writes into the active Session. One draft per Part,
     kept in window state, so switching Parts or modes never loses text.
   - Self mode — the viewed Session's entries grouped by Part: what was
     said to the system this Session. Read-only; a Part heading selects
     that Part and switches to Part mode.

   Like notes, entries can be edited or deleted from the present; in
   Time-travel everything is read-only and the composer is hidden (the
   canvas's read-only gate). Sending is explicit (Send, or Cmd/Ctrl+Enter) —
   an entry is an event, not a field to autosave."
  (:require
   [aps.parts.common.constants :refer [conversation-speakers max-text-length]]
   [aps.parts.frontend.components.inline-edit :refer [commit-value]]
   [aps.parts.frontend.components.window :refer [window]]
   [aps.parts.frontend.state.conversations :as c]
   [clojure.string :as str]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-state]]
   [uix.re-frame :as uix.rf]))

(defn- cmd-enter? [^js e]
  (and (= "Enter" (.-key e)) (or (.-metaKey e) (.-ctrlKey e))))

(defui ^:private segmented
  "A small pressed-state button group. `options` is `[[value label
   disabled?] …]`."
  [{:keys [options value on-select class]}]
  ($ :div {:class (str "join " class)}
     (for [[v label disabled?] options]
       ($ :button {:key          (str v)
                   :type         "button"
                   :disabled     disabled?
                   :aria-pressed (= v value)
                   :class        (str "btn btn-xs join-item" (when (= v value) " btn-active"))
                   :on-click     #(on-select v)}
          label))))

(defui ^:private entry-row
  "One entry: speaker, text, and — on an editable canvas — Edit and
   Delete. Editing happens in place with its own Save / Cancel."
  [{:keys [entry part editable?]}]
  (let [[draft set-draft] (use-state nil)
        save!             (fn []
                            (when-let [text (commit-value draft (:text entry) seq)]
                              (rf/dispatch [:map/conversation-entry-update
                                            (:id entry) {:text text}]))
                            (set-draft nil))]
    ($ :li {:class "conversation-entry"}
       ($ :div {:class "flex items-baseline justify-between gap-2"}
          ($ :span {:class "conversation-speaker"}
             (c/speaker-label (:speaker entry) part))
          (when (and editable? (nil? draft))
            ($ :span {:class "flex gap-2"}
               ($ :button {:type     "button"
                           :class    "link link-hover text-xs text-base-content/60"
                           :on-click #(set-draft (:text entry))}
                  "Edit")
               ($ :button {:type     "button"
                           :class    "link link-hover text-xs text-base-content/60"
                           :on-click #(rf/dispatch [:map/conversation-entry-remove (:id entry)])}
                  "Delete"))))
       (if draft
         ($ :div {:class "mt-1"}
            ($ :textarea {:max-length  max-text-length
                          :class       "textarea textarea-sm w-full"
                          :aria-label  "Edit entry"
                          :auto-focus  true
                          :value       draft
                          :on-change   #(set-draft (.. % -target -value))
                          :on-key-down (fn [e]
                                         (cond
                                           (cmd-enter? e) (save!)
                                           (= "Escape" (.-key e))
                                           (do (.preventDefault e) (set-draft nil))))})
            ($ :div {:class "flex justify-end gap-2 mt-1"}
               ($ :button {:type "button" :class "btn btn-xs" :on-click #(set-draft nil)}
                  "Cancel")
               ($ :button {:type     "button"
                           :class    "btn btn-xs btn-primary"
                           :disabled (str/blank? draft)
                           :on-click save!}
                  "Save")))
         ($ :p {:class "text-sm whitespace-pre-wrap"} (:text entry))))))

(defui ^:private composer
  "Speaker control, text, Send. The draft `{:speaker :text}` lives in
   window state keyed by Part id."
  [{:keys [part]}]
  (let [part-id (:id part)
        draft   (or (uix.rf/use-subscribe [:ui/window-draft :conversation part-id])
                    {:speaker "self" :text ""})
        update! #(rf/dispatch [:window/set-draft :conversation part-id (merge draft %)])
        text    (str/trim (:text draft))
        send!   (fn []
                  (when (seq text)
                    (rf/dispatch [:map/conversation-entry-create part-id (:speaker draft) text])
                    (rf/dispatch [:window/clear-draft :conversation part-id])))]
    ($ :div {:class "conversation-composer"}
       ($ :textarea {:max-length  max-text-length
                     :class       "floating-window-text"
                     :aria-label  "New entry"
                     :placeholder "What was said or done — [in brackets] for actions"
                     :value       (:text draft)
                     :on-change   #(update! {:text (.. % -target -value)})
                     :on-key-down #(when (cmd-enter? %) (send!))})
       ($ :div {:class "floating-window-actions"}
          ($ segmented {:class     "flex-1"
                        :options   (for [sp conversation-speakers]
                                     [sp (c/speaker-label sp part)])
                        :value     (:speaker draft)
                        :on-select #(update! {:speaker %})})
          ($ :button {:type     "button"
                      :class    "btn btn-sm btn-primary"
                      :disabled (empty? text)
                      :on-click send!}
             "Send")))))

(defui ^:private part-view
  [{:keys [part entries editable?]}]
  (let [mine (c/for-part entries (:id part))]
    ($ :<>
       ($ :div {:class "conversation-log"}
          (if (empty? mine)
            ($ :p {:class "text-sm text-base-content/50 italic"} "Nothing recorded yet")
            (for [[ordinal es] (c/by-session mine)]
              ($ :section {:key (str "s" ordinal)}
                 (when ordinal
                   ($ :h4 {:class "conversation-heading"} (str "Session " ordinal)))
                 ($ :ul
                    (for [e es]
                      ($ entry-row {:key       (:id e)
                                    :entry     e
                                    :part      part
                                    :editable? editable?})))))))
       (when editable?
         ($ composer {:part part})))))

(defui ^:private self-view
  [{:keys [entries labels ordinal]}]
  (let [shown (if ordinal (filterv #(= (:first_appeared_ordinal %) ordinal) entries) entries)]
    ($ :div {:class "conversation-log"}
       (if (empty? shown)
         ($ :p {:class "text-sm text-base-content/50 italic"}
            "Nothing recorded in this Session")
         (for [[pid es] (c/by-part shown)
               :let     [part {:id pid :label (labels pid)}]]
           ($ :section {:key pid}
              ($ :h4 {:class "conversation-heading"}
                 ($ :button {:type     "button"
                             :class    "link link-hover"
                             :on-click #(rf/dispatch [:conversation/show-part pid])}
                    (:label part)))
              ($ :ul
                 (for [e es]
                   ($ entry-row {:key (:id e) :entry e :part part :editable? false})))))))))

(defui conversation-window
  []
  (let [labels     (uix.rf/use-subscribe [:canvas/part-labels])
        scope-part (uix.rf/use-subscribe [:conversation/scope-part])
        chosen     (uix.rf/use-subscribe [:conversation/chosen-mode])
        entries    (uix.rf/use-subscribe [:canvas/conversation-entries])
        editable?  (uix.rf/use-subscribe [:canvas/editable?])
        viewed     (uix.rf/use-subscribe [:canvas/viewed-session])
        mode       (c/window-mode chosen scope-part)]
    ($ window {:kind  :conversation
               :class "conversation"
               :title (if (= mode :part)
                        (str "Conversation · " (:label scope-part))
                        (cond-> "Conversation"
                          viewed (str " · Session " (:ordinal viewed))))}
       ($ segmented {:class     "conversation-modes"
                     :options   [[:part "Part" (nil? scope-part)] [:self "Self"]]
                     :value     mode
                     :on-select #(rf/dispatch [:conversation/show %])})
       (if (= mode :part)
         ($ part-view {:part      scope-part
                       :entries   entries
                       :editable? editable?})
         ($ self-view {:entries entries
                       :labels  labels
                       :ordinal (:ordinal viewed)})))))
