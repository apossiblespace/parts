(ns aps.parts.frontend.components.toolbar.session-card
  "Permanently visible Session card at the top of the sidebar
   (ADR-0014): the trigger — the Session's clinical frame — stays in
   view while mapping. Three jobs:
   - Editing mode: a truncated trigger preview plus Add/Edit, which
     opens a modal textarea (multi-line triggers are welcome).
   - Time-travel: the viewed Session's trigger, read-only — See more
     opens the same modal in read-only form.
   - No-session (read-only Map): the start-a-session call to action.
   Session errors (undo/save refusals) surface here as well."
  (:require
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.components.modal :refer [modal]]
   [aps.parts.frontend.components.toolbar.header :refer [header]]
   [aps.parts.frontend.dates :as dates]
   [aps.parts.frontend.state.sessions :as sessions]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui use-ref use-state]]
   [uix.re-frame :as uix.rf]))

(def ^:private preview-chars 120)

(defui ^:private trigger-modal
  "`mode` is :edit (textarea + Save) or :view (read-only) — the see-more
   target from either app mode; nil keeps the dialog closed."
  [{:keys [mode session on-close]}]
  (let [text-ref (use-ref nil)]
    ($ modal {:show      (some? mode)
              :title     (str "Session " (:ordinal session) " — trigger")
              :box-class "w-96"
              :on-close  on-close}
       (if (= :view mode)
         ($ :p {:class "text-xs whitespace-pre-wrap"} (:trigger session))
         ($ :<>
            ($ :textarea {:key           (str (:id session) (some? mode))
                          :ref           text-ref
                          :class         "textarea textarea-sm w-full h-40"
                          :aria-label    "Session trigger"
                          :placeholder   "e.g. a conflict at work"
                          :default-value (or (:trigger session) "")})
            ($ :div {:class "modal-action flex space-x-2"}
               ($ :button {:type     "button"
                           :class    "btn btn-sm flex-1"
                           :on-click on-close}
                  "Cancel")
               ($ :button {:type     "button"
                           :class    "btn btn-sm btn-primary flex-1"
                           :on-click (fn []
                                       (rf/dispatch
                                        [:session/set-trigger (:id session)
                                         (.-value @text-ref)])
                                       (on-close))}
                  "Save")))))))

(defui ^:private trigger-preview
  "Truncated trigger text with the see-more affordance."
  [{:keys [trigger on-see-more]}]
  (let [{:keys [preview truncated?]} (sessions/trigger-preview
                                      trigger preview-chars)]
    ($ :<>
       ($ :p {:class "text-xs whitespace-pre-line"}
          preview
          (when truncated? "…"))
       (when truncated?
         ($ :button {:class    "btn btn-xs btn-ghost"
                     :on-click on-see-more}
            "See more")))))

(defui session-card
  "Renders nothing until the Session list has loaded (and never in the
   playground, which has no Sessions)."
  []
  (let [the-sessions            (uix.rf/use-subscribe [:map/sessions])
        active                  (uix.rf/use-subscribe [:session/active])
        travelling?             (uix.rf/use-subscribe [:time-travel/active?])
        viewing                 (uix.rf/use-subscribe [:time-travel/viewing])
        saved?                  (uix.rf/use-subscribe [:session/trigger-saved?])
        error                   (uix.rf/use-subscribe [:ui/session-error])
        [modal-mode set-modal!] (use-state nil)
        session                 (if travelling? (:session viewing) active)
        date                    (dates/format-date dates/medium-date-format
                                                   (:anchor_valid_at session))
        has-trigger?            (seq (:trigger session))]
    (when (some? the-sessions)
      ($ :div {:class "tools session-tools"}
         ($ header {:title (if session
                             (str "Session " (:ordinal session))
                             "Session")
                    :right date})
         ($ :div {:class "p-2 space-y-2"}
            (cond
              (nil? active)
              ($ :<>
                 ($ :p {:class "text-xs"}
                    "This map is read-only until a session is started.")
                 ($ :button {:class    "btn btn-sm btn-primary w-full"
                             :on-click (fn []
                                         (o/track "Session started" {})
                                         (rf/dispatch [:session/start]))}
                    "Start session"))

              travelling?
              (if has-trigger?
                ($ trigger-preview {:trigger     (:trigger session)
                                    :on-see-more #(set-modal! :view)})
                ($ :p {:class "text-xs text-base-content/50 italic"}
                   "No trigger recorded"))

              :else
              ($ :<>
                 (when has-trigger?
                   ($ trigger-preview {:trigger     (:trigger session)
                                       :on-see-more #(set-modal! :view)}))
                 ($ :div {:class "flex items-center justify-between"}
                    ($ :button {:class    "btn btn-xs"
                                :on-click #(set-modal! :edit)}
                       (if has-trigger? "Edit trigger" "Add trigger"))
                    (when saved?
                      ($ :span {:class "text-xs text-base-content/60"}
                         "Saved")))))
            (when error
              ($ :p {:class "text-error text-xs" :role "alert"} error)))
         ($ trigger-modal {:mode     modal-mode
                           :session  session
                           :on-close #(set-modal! nil)})))))
