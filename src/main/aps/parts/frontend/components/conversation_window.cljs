(ns aps.parts.frontend.components.conversation-window
  "The conversation floating window (ADR-0017, ADR-0018), laid out as a
   log viewer. Part mode: one Part's conversation, editable. All mode:
   every Part, grouped by Part inside each Session, read-only.

   In Time-travel the entries are the snapshot's, so nothing after the
   viewed Session shows."
  (:require
   [aps.parts.common.constants :refer [conversation-speakers max-text-length part-colors part-labels]]
   [aps.parts.frontend.components.inline-edit :refer [commit-value]]
   [aps.parts.frontend.components.window :refer [window]]
   [aps.parts.frontend.dates :as dates]
   [aps.parts.frontend.state.conversations :as c]
   [clojure.string :as str]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect use-ref use-state]]
   [uix.re-frame :as uix.rf]))

(defn- return?
  "Return without Shift, and not an IME confirming a character (Safari
   flags that one with keyCode 229, not isComposing)."
  [^js e]
  (and (= "Enter" (.-key e))
       (not (.-shiftKey e))
       (not (.. e -nativeEvent -isComposing))
       (not= 229 (.-keyCode e))))

(defui ^:private segmented
  "A small pressed-state button group. `options` is `[[value label
   disabled?] …]`."
  [{:keys [options value on-select class label]}]
  ($ :div {:class (str "join " class) :role "group" :aria-label label}
     (for [[v label disabled?] options]
       ($ :button {:key          (str v)
                   :type         "button"
                   :disabled     disabled?
                   :aria-pressed (= v value)
                   :class        (str "btn btn-xs join-item" (when (= v value) " btn-active"))
                   :on-click     #(on-select v)}
          label))))

(defui ^:private type-dot
  "A nil `part` keeps the dot's space, so every speaker's text starts at
   the same indent."
  [{:keys [part]}]
  ($ :i {:class       (str "conversation-dot" (when-not part " invisible"))
         :aria-hidden true
         :style       (when part
                        {:background-color (part-colors (keyword (:type part)))})}))

(defui ^:private entry-editor
  [{:keys [entry on-done]}]
  (let [[draft set-draft] (use-state (:text entry))
        save!             (fn []
                            (when-let [text (commit-value draft (:text entry) seq)]
                              (rf/dispatch [:map/conversation-entry-update
                                            (:id entry) {:text text}]))
                            (on-done))]
    ($ :div {:class "conversation-editor"}
       ($ :textarea {:max-length  max-text-length
                     :class       "textarea textarea-sm w-full"
                     :aria-label  "Edit entry"
                     :auto-focus  true
                     :on-focus    (fn [^js e]
                                    (let [t (.-target e) end (.. t -value -length)]
                                      (.setSelectionRange t end end)))
                     :value       draft
                     :on-change   #(set-draft (.. % -target -value))
                     :on-key-down (fn [^js e]
                                    (cond
                                      (return? e)            (do (.preventDefault e) (save!))
                                      (= "Escape" (.-key e)) (do (.preventDefault e) (on-done))))})
       ($ :div {:class "conversation-editor-actions"}
          ($ :button {:type     "button"
                      :class    "btn btn-xs btn-ghost text-error mr-auto"
                      :on-click (fn []
                                  (rf/dispatch [:map/conversation-entry-remove (:id entry)])
                                  (on-done))}
             "Delete")
          ($ :button {:type "button" :class "btn btn-xs" :on-click on-done}
             "Cancel")
          ($ :button {:type     "button"
                      :class    "btn btn-xs btn-primary"
                      :disabled (str/blank? draft)
                      :on-click save!}
             "Save")))))

(defui ^:private entry-runs
  [{:keys [entries part editable?]}]
  (let [[editing set-editing] (use-state nil)]
    ($ :<>
       (for [[speaker es] (c/runs entries)]
         ($ :div {:key (:id (first es)) :class "conversation-run"}
            ($ :div {:class "conversation-speaker" :data-speaker speaker}
               ($ type-dot {:part (when (= speaker "part") part)})
               (c/speaker-label speaker part))
            (for [e es]
              (if (and editable? (= (:id e) editing))
                ($ entry-editor {:key (:id e) :entry e :on-done #(set-editing nil)})
                ($ :p {:key             (:id e)
                       :class           (str "conversation-text" (when editable? " editable"))
                       :on-double-click (when editable? #(set-editing (:id e)))}
                   (:text e)))))))))

(defui ^:private session-section
  "Entries from before the first Session (demo Maps have none) get no
   heading."
  [{:keys [ordinal session children]}]
  ($ :section {:data-ordinal ordinal}
     (when ordinal
       ($ :h4 {:class "conversation-session"}
          (str "Session " ordinal)
          (when-let [d (dates/format-date dates/short-date-format (:anchor_valid_at session))]
            ($ :span {:class "conversation-session-date"} d))))
     children))

(defui ^:private composer
  [{:keys [part]}]
  (let [part-id (:id part)
        draft   (or (uix.rf/use-subscribe [:ui/window-draft :conversation part-id])
                    {:speaker "self" :text ""})
        update! #(rf/dispatch [:window/set-draft :conversation part-id (merge draft %)])
        text    (str/trim (:text draft))
        add!    (fn []
                  (when (seq text)
                    (rf/dispatch [:map/conversation-entry-create part-id (:speaker draft) text])
                    (update! {:text ""})))]
    ($ :div {:class "conversation-composer"}
       ($ :div {:class "flex items-center gap-2"}
          ($ :span {:class "text-xs text-base-content/60" :aria-hidden true} "Speaker")
          ($ segmented {:label     "Speaker"
                        :options   (for [sp conversation-speakers]
                                     [sp ($ :<>
                                            (when (= sp "part") ($ type-dot {:part part}))
                                            (c/speaker-label sp part))])
                        :value     (:speaker draft)
                        :on-select #(update! {:speaker %})}))
       ($ :div {:class "conversation-composer-field"}
          ($ :textarea {:max-length  max-text-length
                        :rows        1
                        :aria-label  "New entry"
                        :placeholder (if (= "therapist" (:speaker draft))
                                       "What the therapist said or did"
                                       (str "What " (c/speaker-label (:speaker draft) part)
                                            " said or did"))
                        :value       (:text draft)
                        :on-change   #(update! {:text (.. % -target -value)})
                        :on-key-down (fn [^js e]
                                       (when (return? e)
                                         (.preventDefault e)
                                         (add!)))})
          ($ :button {:type     "button"
                      :class    "btn btn-xs"
                      :disabled (empty? text)
                      :on-click add!}
             "Add")))))

(defn- scroll-to!
  "All mode lands on the viewed Session; Part mode, or a viewed Session
   with no entries, on the newest entry."
  [^js log mode viewed-ordinal]
  (set! (.-scrollTop log)
        (if-let [^js s (and (= mode :all) viewed-ordinal
                            (.querySelector log (str "section[data-ordinal=\"" viewed-ordinal "\"]")))]
          (.-offsetTop s)
          (.-scrollHeight log))))

(defui conversation-window
  []
  (let [names      (uix.rf/use-subscribe [:canvas/part-names])
        scope-part (uix.rf/use-subscribe [:conversation/scope-part])
        chosen     (uix.rf/use-subscribe [:conversation/chosen-mode])
        entries    (uix.rf/use-subscribe [:canvas/conversation-entries])
        editable?  (uix.rf/use-subscribe [:canvas/editable?])
        viewed     (:ordinal (uix.rf/use-subscribe [:canvas/viewed-session]))
        sessions   (uix.rf/use-subscribe [:map/sessions])
        mode       (c/window-mode chosen scope-part)
        shown      (if (= mode :part) (c/for-part entries (:id scope-part)) entries)
        by-ordinal (into {} (map (juxt :ordinal identity)) sessions)
        log-ref    (use-ref nil)]
    ;; A new last entry re-scrolls (an Add); an edit or a delete mid-log does not.
    (use-effect
     (fn [] (some-> @log-ref (scroll-to! mode viewed)))
     [mode (:id scope-part) viewed (:id (peek shown))])
    ($ window {:kind     :conversation
               :class    "conversation"
               :title    "Conversation"
               :controls ($ segmented {:class     "min-w-0"
                                       :label     "Scope"
                                       :options   [[:all "All"]
                                                   [:part (if scope-part
                                                            ($ :<>
                                                               ($ type-dot {:part scope-part})
                                                               ($ :span {:class "truncate max-w-36"}
                                                                  (:label scope-part)))
                                                            "No Part selected")
                                                    (nil? scope-part)]]
                                       :value     mode
                                       :on-select #(rf/dispatch [:conversation/show %])})}
       ($ :div {:ref log-ref :class "conversation-log"}
          (if (empty? shown)
            ($ :p {:class "p-3 text-sm italic text-base-content/50"} "Nothing recorded yet")
            (for [[ordinal es] (c/by-session shown)]
              ($ session-section {:key (str "s" ordinal) :ordinal ordinal :session (by-ordinal ordinal)}
                 (if (= mode :part)
                   ($ entry-runs {:entries es :part scope-part :editable? editable?})
                   (for [[pid pes] (c/by-part es)
                         :let      [part (names pid)]]
                     ($ :div {:key pid}
                        ($ :div {:class "conversation-part"}
                           ($ :h5 {:class "flex items-center gap-2"}
                              ($ type-dot {:part part})
                              (:label part)
                              ($ :span {:class "conversation-part-type"}
                                 (get-in part-labels [(keyword (:type part)) :label])))
                           ($ :button {:type     "button"
                                       :class    "btn btn-xs ml-auto"
                                       :title    (str "Show only " (:label part))
                                       :on-click #(rf/dispatch [:conversation/show-part pid])}
                              "Show"))
                        ($ entry-runs {:entries pes :part part}))))))))
       (when (and (= mode :part) editable?)
         ($ composer {:part scope-part})))))
