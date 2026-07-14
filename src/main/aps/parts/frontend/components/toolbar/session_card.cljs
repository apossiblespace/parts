(ns aps.parts.frontend.components.toolbar.session-card
  "Permanently visible Trigger card at the top of the sidebar
   (ADR-0014): the trigger — the Session's clinical frame — stays in
   view while mapping. Session identity (ordinal + date) is NOT shown
   here: the top chrome owns it (map-name widget segment in Editing,
   the navigation bar in Time-travel); the full date lives in the
   modal title. Two jobs (a no-session Map cannot exist — Maps are born
   with Session 1):
   - Editing mode: a truncated trigger preview plus Add…/Edit… in the
     header band, which opens a modal textarea (multi-line triggers
     are welcome).
   - Time-travel: the viewed Session's trigger, read-only — See more
     opens the same modal in read-only form.
   Session errors (undo/save refusals) surface here as well."
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.components.modal :refer [modal]]
   [aps.parts.frontend.components.toolbar.header :refer [header]]
   [aps.parts.frontend.dates :as dates]
   [aps.parts.frontend.state.sessions :as sessions]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect use-ref use-state]]
   [uix.re-frame :as uix.rf]))

(def ^:private preview-chars 120)

(defui ^:private trigger-modal
  "`mode` is :edit (textarea + Save) or :view (read-only) — the see-more
   target from either app mode; nil keeps the dialog closed. `on-edit`
   switches :view to :edit in place; omitted in Time-travel, where the
   past is not editable. Both modes share one box size and text size so
   the switch doesn't jump."
  [{:keys [mode session on-close on-edit]}]
  (let [text-ref (use-ref nil)
        save!    (fn []
                   (rf/dispatch [:session/set-trigger (:id session)
                                 (.trim (.-value @text-ref))])
                   (on-close))]
    ;; Focus the textarea once the dialog is open, caret at the end.
    ;; (An autofocus prop fires before `showModal` and is lost — the
    ;; modal child's effect opens the dialog first, then this runs.)
    (use-effect
     (fn []
       (when (and (= :edit mode) @text-ref)
         (let [end (.-length (.-value @text-ref))]
           (.focus @text-ref)
           (.setSelectionRange @text-ref end end))))
     [mode])
    ;; The card header says only "Trigger"; the title carries the full
    ;; session identity, the one place it appears in the sidebar's flow.
    ($ modal {:show      (some? mode)
              :title     (str "Session " (:ordinal session) " · "
                              (dates/format-date dates/medium-date-format
                                                 (:anchor_valid_at session)))
              :box-class "modal-box-fixed"
              :on-close  on-close}
       (if (= :view mode)
         ($ :<>
            ($ :p {:class "text-sm whitespace-pre-wrap max-h-[60vh] overflow-y-auto"}
               (:trigger session))
            ($ :div {:class "modal-action flex space-x-2"}
               ($ :button {:type     "button"
                           :class    "btn btn-sm flex-1"
                           :on-click on-close}
                  "Close")
               (when on-edit
                 ($ :button {:type     "button"
                             :class    "btn btn-sm btn-primary flex-1"
                             :on-click on-edit}
                    "Edit"))))
         ($ :<>
            ($ :textarea {:key           (str (:id session) (some? mode))
                          :ref           text-ref
                          :class         "textarea textarea-sm w-full h-64"
                          :aria-label    "Session trigger"
                          :placeholder   "e.g. a conflict at work"
                          :on-key-down   (fn [e]
                                           (when (and (= "Enter" (.-key e))
                                                      (or (.-metaKey e)
                                                          (.-ctrlKey e)))
                                             (save!)))
                          :default-value (or (:trigger session) "")})
            ($ :div {:class "modal-action flex space-x-2"}
               ($ :button {:type     "button"
                           :class    "btn btn-sm flex-1"
                           :on-click on-close}
                  "Cancel")
               ($ :button {:type     "button"
                           :class    "btn btn-sm btn-primary flex-1"
                           :on-click save!}
                  "Save")))))))

(defui ^:private trigger-preview
  "Truncated trigger text; the see-more disclosure sits inline at the
   end of the text it discloses, like a mail-preview 'more' link."
  [{:keys [trigger on-see-more]}]
  (let [{:keys [preview truncated?]} (sessions/trigger-preview
                                      trigger preview-chars)]
    ($ :p {:class "text-xs whitespace-pre-line"}
       preview
       (when truncated?
         ($ :<>
            "… "
            ($ :button {:class    "link link-hover text-base-content/60"
                        :on-click on-see-more}
               "See more"))))))

(defui ^:private activation-row
  "Which Part the Session activated (session_activations, ADR-0014):
   a discrete control, so it commits on change (the silent-autosave
   convention)."
  [{:keys [session parts read-only?]}]
  (let [activated-id (:activated_part_id session)]
    (if read-only?
      (when activated-id
        ($ :p {:class "text-xs"}
           ($ :span {:class "text-base-content/60"} "Activated part: ")
           (:label (some #(when (= (:id %) activated-id) %) parts))))
      ($ :label {:class "block text-xs space-y-1"}
         ($ :span {:class "text-base-content/60"} "Activated part")
         ($ :select
            {:class      "select select-xs w-full"
             :aria-label "Activated part"
             :value      (or activated-id "")
             :on-change  (fn [e]
                           (let [v       (.. e -target -value)
                                 part-id (when (seq v) v)]
                             (o/track (if part-id
                                        "Session activation set"
                                        "Session activation cleared") {})
                             (rf/dispatch [:session/set-activation
                                           (:id session) part-id])))}
            ($ :option {:value ""} "None")
            (map (fn [part]
                   ($ :option {:key (:id part) :value (:id part)}
                      (:label part)))
                 parts))))))

(defui session-card
  "Renders nothing until the Session list has loaded (and never in the
   playground, which has no Sessions)."
  []
  (let [the-sessions            (uix.rf/use-subscribe [:map/sessions])
        active                  (uix.rf/use-subscribe [:session/active])
        travelling?             (uix.rf/use-subscribe [:time-travel/active?])
        viewing                 (uix.rf/use-subscribe [:time-travel/viewing])
        ;; The activation row rides the same seams as the canvas
        ;; marker — the SHOWN Session joined against the Parts on
        ;; screen — so card and marker cannot disagree.
        viewed-session          (uix.rf/use-subscribe [:canvas/viewed-session])
        part-options            (uix.rf/use-subscribe [:canvas/part-options])
        error                   (uix.rf/use-subscribe [:ui/session-error])
        ;; Snapshot-fetch failures surface here since the navigation
        ;; steppers (map.cljs) have no room for text.
        tt-error                (uix.rf/use-subscribe [:time-travel/error])
        [modal-mode set-modal!] (use-state nil)
        session                 (if travelling? (:session viewing) active)
        has-trigger?            (seq (:trigger session))
        ;; The band-height compensation (-my-1) keeps this card's header
        ;; the same height as the button-less bands of the other cards.
        edit-button             (when (and active (not travelling?))
                                  ($ :button
                                     {:class      "btn btn-xs -my-1"
                                      :aria-label (if has-trigger?
                                                    "Edit trigger"
                                                    "Add trigger")
                                      :on-click   #(set-modal! :edit)}
                                     (if has-trigger? "Edit…" "Add…")))
        body                    (if has-trigger?
                                  ($ trigger-preview {:trigger     (:trigger session)
                                                      :on-see-more #(set-modal! :view)})
                                  ($ :p {:class "text-xs text-base-content/50 italic"}
                                     "No trigger recorded"))]
    (when (some? the-sessions)
      ($ :div {:class "tools session-tools"}
         ($ header {:title "Session trigger"
                    :right edit-button})
         ($ :div {:class "p-2 space-y-2"}
            body
            (when viewed-session
              ($ activation-row {:session    viewed-session
                                 :parts      part-options
                                 :read-only? travelling?}))
            (for [msg (remove nil? [error tt-error])]
              ($ :p {:key   msg
                     :class "text-error text-xs"
                     :role  "alert"}
                 msg)))
         ($ trigger-modal {:mode     modal-mode
                           :session  session
                           :on-close #(set-modal! nil)
                           :on-edit  (when-not travelling?
                                       #(set-modal! :edit))})))))
