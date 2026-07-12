(ns aps.parts.frontend.components.session-chip
  "The Session control in the Map's top-left row (ADR-0014): a chip
   showing the active Session, opening a popover that edits its trigger,
   starts the next Session, or undoes a just-started one. Permanently a
   single-session control — history browsing is Time-travel mode's job
   (TASK-073.03), not a list in this dropdown.

   Instant start, no modal: starting creates the Session immediately and
   the popover's trigger field — autofocused while empty — is the
   optional trigger prompt."
  (:require
   ["lucide-react" :refer [ChevronDown]]
   [aps.parts.common.observe :as o]
   [aps.parts.frontend.dates :as dates]
   [aps.parts.frontend.state.sessions :as sessions]
   [re-frame.core :as rf]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as uix.rf]))

(defn- commit-trigger
  "Save the trigger on blur when it actually changed; Enter just blurs."
  [session ^js event]
  (let [value (.. event -target -value)]
    (when-not (= value (or (:trigger session) ""))
      (rf/dispatch [:session/set-trigger (:id session) value]))))

(defui ^:private active-session-popover
  ;; "Undo new session" shows only inside the client-tracked undo window
  ;; (see `state/sessions`), so it can never draw a server refusal —
  ;; there is no delete affordance outside that window.
  [{:keys [session]}]
  (let [undoable? (uix.rf/use-subscribe [:session/undoable?])
        saved?    (uix.rf/use-subscribe [:session/trigger-saved?])]
    ($ :div {:class "space-y-2"}
       ($ :div {:class "flex items-baseline justify-between gap-3"}
          ($ :span {:class "font-medium text-sm"}
             (str "Session " (:ordinal session)))
          ($ :span {:class "text-xs text-base-content/60"}
             (dates/format-date dates/medium-date-format
                                (:anchor_valid_at session))))
       ($ :label {:class "flex flex-col gap-1"}
          ($ :span {:class "flex justify-between text-xs text-base-content/60"}
             "Trigger (optional)"
             ;; Quiet save confirmation: appears once the PUT round-trips,
             ;; withdrawn as soon as the text changes again.
             (when saved?
               ($ :span "Saved")))
          ($ :input {:key           (:id session)
                     :class         "input input-sm w-full"
                     :type          "text"
                     :aria-label    "Session trigger"
                     :placeholder   "e.g. a conflict at work"
                     :default-value (or (:trigger session) "")
                     ;; The optional-trigger prompt after an instant start:
                     ;; a fresh Session mounts this empty input focused.
                     :auto-focus    (empty? (:trigger session))
                     :on-blur       #(commit-trigger session %)
                     :on-key-down   (fn [^js e]
                                      (when (= "Enter" (.-key e))
                                        (.blur (.-target e))))}))
       ($ :div {:class "flex flex-col gap-1 pt-1 border-t border-base-300"}
          ($ :button {:class    "btn btn-sm btn-primary w-full"
                      :on-click (fn []
                                  (o/track "Session started" {})
                                  (rf/dispatch [:session/start]))}
             "Start new session")
          (when undoable?
            ($ :button {:class    "btn btn-sm btn-ghost w-full"
                        :on-click (fn []
                                    (o/track "Session undone" {})
                                    (rf/dispatch [:session/delete (:id session)]))}
               "Undo new session"))))))

(defui ^:private no-session-popover
  []
  ($ :div {:class "space-y-2"}
     ($ :p {:class "text-sm"}
        "This map is read-only until a session is started.")
     ($ :button {:class    "btn btn-sm btn-primary w-full"
                 :on-click (fn []
                             (o/track "Session started" {})
                             (rf/dispatch [:session/start]))}
        "Start session")))

(defui session-chip
  "Renders nothing until the Session list has loaded — a chip guessing
   'no sessions' before the fetch lands would flash a false read-only
   call-to-action on every Map open."
  []
  (let [the-sessions (uix.rf/use-subscribe [:map/sessions])
        active       (uix.rf/use-subscribe [:session/active])
        error        (uix.rf/use-subscribe [:ui/session-error])]
    (when (some? the-sessions)
      ;; flex, not daisyUI's inline-block default: the inline box's
      ;; line-box strut would make this the row's tallest child and
      ;; stretch every sibling join out of alignment.
      ($ :div {:class "dropdown dropdown-bottom flex shadow-xs"}
         ($ :div {:tabIndex   0
                  :role       "button"
                  :class      "btn btn-sm bg-base-100 flex items-center gap-1.5"
                  :aria-label (if active
                                (sessions/display-label active)
                                "Start a session")}
            (if active
              (str "Session " (:ordinal active)
                   (when-let [date (dates/format-date
                                    dates/short-date-format
                                    (:anchor_valid_at active))]
                     (str " · " date)))
              "Start a session")
            ($ ChevronDown {:size 16}))
         ($ :div {:tabIndex 0
                  :class    (str "dropdown-content z-10 mt-1 w-72 p-3 "
                                 "rounded-box border border-base-300 "
                                 "bg-base-100 shadow-md")}
            (if active
              ($ active-session-popover {:session active})
              ($ no-session-popover))
            (when error
              ($ :p {:class "text-error text-xs mt-2" :role "alert"}
                 error)))))))
