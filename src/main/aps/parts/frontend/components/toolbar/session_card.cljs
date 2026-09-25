(ns aps.parts.frontend.components.toolbar.session-card
  "Permanently visible Trigger card at the top of the sidebar
   (ADR-0014): the trigger — the Session's clinical frame — stays in
   view while mapping. Session identity (ordinal + date) is NOT shown
   here: the top chrome owns it (map-name widget segment in Editing,
   the navigation bar in Time-travel); the full date lives in the
   modal title. Two jobs (a no-session Map cannot exist — Maps are born
   with Session 1):
   - Editing mode: a truncated trigger preview plus Add…/Edit… in the
     header band, which opens the trigger floating window (ADR-0017;
     multi-line triggers are welcome).
   - Time-travel: the viewed Session's trigger, read-only — See more
     opens the same window in read-only form.
   Session errors (undo/save refusals) surface here as well."
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.components.toolbar.header :refer [header]]
   [aps.parts.frontend.components.window :refer [window window-actions]]
   [aps.parts.frontend.dates :as dates]
   [aps.parts.frontend.state.sessions :as sessions]
   [clojure.string :as str]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-effect use-ref]]
   [uix.re-frame :as uix.rf]))

(def ^:private preview-chars 120)

(defui trigger-window
  "The Session trigger floating window (ADR-0017). Scope: the viewed
   Session, so it follows Session navigation. Edits a draft keyed by
   Session id: Save commits (`:session/set-trigger`), Cancel discards,
   Close/Escape keep it — so the active Session's draft waits out a
   Time-travel visit, where the past Session shows read-only. The title
   carries the full Session identity, the one place it appears in the
   sidebar's flow."
  []
  (let [session     (uix.rf/use-subscribe [:canvas/viewed-session])
        travelling? (uix.rf/use-subscribe [:time-travel/active?])
        session-id  (:id session)
        saved       (or (:trigger session) "")
        draft       (uix.rf/use-subscribe [:ui/window-draft :trigger session-id])
        text        (or draft saved)
        dirty?      (and (some? draft) (not= draft saved))
        text-ref    (use-ref nil)
        cancel!     #(rf/dispatch [:window/clear-draft :trigger session-id])
        save!       (fn []
                      (rf/dispatch [:session/set-trigger session-id (str/trim text)])
                      (cancel!))]
    ;; Focus the textarea once it exists, caret at the end.
    (use-effect
     (fn []
       (when-let [t @text-ref]
         (let [end (.-length (.-value t))]
           (.focus t)
           (.setSelectionRange t end end))))
     [session-id travelling?])
    (when session
      ($ window {:kind  :trigger
                 :class "text-window"
                 :title (str "Session " (:ordinal session) ", "
                             (dates/format-date dates/medium-date-format
                                                (:anchor_valid_at session)))}
         (if travelling?
           (if (seq saved)
             ($ :p {:class "text-sm whitespace-pre-wrap"} saved)
             ($ :p {:class "text-sm text-base-content/50 italic"}
                "No trigger recorded"))
           ($ :<>
              ($ :textarea {:ref         text-ref
                            :class       "floating-window-text"
                            :aria-label  "Session trigger"
                            :placeholder "e.g. a conflict at work"
                            :value       text
                            :on-change   #(rf/dispatch [:window/set-draft :trigger session-id
                                                        (.. % -target -value)])
                            :on-key-down (fn [^js e]
                                           (when (and dirty?
                                                      (= "Enter" (.-key e))
                                                      (or (.-metaKey e) (.-ctrlKey e)))
                                             (save!)))})
              ($ window-actions {:dirty?    dirty?
                                 :on-save   save!
                                 :on-cancel cancel!})))))))

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
  (let [the-sessions   (uix.rf/use-subscribe [:map/sessions])
        active         (uix.rf/use-subscribe [:session/active])
        travelling?    (uix.rf/use-subscribe [:time-travel/active?])
        viewing        (uix.rf/use-subscribe [:time-travel/viewing])
        ;; The activation row rides the same seams as the canvas
        ;; marker — the SHOWN Session joined against the Parts on
        ;; screen — so card and marker cannot disagree.
        viewed-session (uix.rf/use-subscribe [:canvas/viewed-session])
        part-options   (uix.rf/use-subscribe [:canvas/part-options])
        error          (uix.rf/use-subscribe [:ui/session-error])
        ;; Snapshot-fetch failures surface here since the navigation
        ;; steppers (map.cljs) have no room for text.
        tt-error       (uix.rf/use-subscribe [:time-travel/error])
        open-window!   #(rf/dispatch [:window/open :trigger])
        session        (if travelling? (:session viewing) active)
        has-trigger?   (seq (:trigger session))
        ;; The band-height compensation (-my-1) keeps this card's header
        ;; the same height as the button-less bands of the other cards.
        edit-button    (when (and active (not travelling?))
                         ($ :button
                            {:class      "btn btn-xs -my-1"
                             :aria-label (if has-trigger?
                                           "Edit trigger"
                                           "Add trigger")
                             :on-click   open-window!}
                            (if has-trigger? "Edit…" "Add…")))
        body           (if has-trigger?
                         ($ trigger-preview {:trigger     (:trigger session)
                                             :on-see-more open-window!})
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
            ;; Self mode, opened at the top of this Session.
            ($ :button {:type     "button"
                        :class    "btn btn-xs w-full"
                        :on-click #(rf/dispatch [:conversation/show :self])}
               "Session conversation")
            (for [msg (remove nil? [error tt-error])]
              ($ :p {:key   msg
                     :class "text-error text-xs"
                     :role  "alert"}
                 msg)))))))
