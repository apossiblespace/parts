(ns parts.frontend.app
  (:require
   ["htmx.org" :default htmx]
   [parts.frontend.components.system :refer [system]]
   [parts.frontend.context :refer [auth-provider]]
   [parts.common.models.part :refer [make-part]]
   [parts.common.models.relationship :refer [make-relationship]]
   [uix.core :refer [$ defui]]
   [uix.dom]
   [uix.re-frame :as uix.rf]
   [re-frame.core :as rf]))

(def system-id (str (random-uuid)))

(def parts
  [(make-part {:type "manager"
               :label "Manager"
               :position_x 300
               :position_y 130
               :system_id system-id})
   (make-part {:type "exile"
               :label "Exile"
               :position_x 200
               :position_y 300
               :system_id system-id})
   (make-part {:type "firefighter"
               :label "Firefighter"
               :position_x 100
               :position_y 130
               :system_id system-id})])

(def relationships
  [(make-relationship {:type "unknown"
                       :source_id (:id (nth parts 0))
                       :target_id (:id (nth parts 1))
                       :system_id system-id})
   (make-relationship {:type "protective"
                       :source_id (:id (nth parts 2))
                       :target_id (:id (nth parts 1))
                       :system_id system-id})])

(def system-data
  {:system
   {:id system-id
    :parts parts
    :relationships relationships}})

(rf/reg-sub
 :app/system
 (fn [db _]
   (:system db)))

(rf/reg-event-fx
 :app/init-db []
 (fn [{:store/keys [system]} [_ default-db]]
   {:db (update default-db :system into system)}))

(rf/reg-event-db
 :part/update-position
 (fn [db [_ node-id position]]
   (update-in db [:system :parts]
              (fn [parts]
                (mapv (fn [part]
                        (if (= (:id part) node-id)
                          (-> part
                              (assoc :position_x (int (:x position)))
                              (assoc :position_y (int (:y position))))
                          part))
                      parts)))))

(rf/reg-event-fx
 :part/finish-position-change
 (fn [{:keys [db]} [_ node-id position]]
   {:db db
    :fx [[:dispatch [:queue/add-event
                     {:entity :node
                      :id node-id
                      :type "position"
                      :data position}]]]}))

(rf/reg-fx
 :queue/add-event
 (fn [event]
   (parts.frontend.api.queue/add-events!
    (:entity event)
    [event])))

(defui app []
  (let [system-state (uix.rf/use-subscribe [:app/system])]
    ($ auth-provider {}
       ($ system system-state))))

(defonce root
  (when-let [root-element (js/document.getElementById "root")]
    (uix.dom/create-root root-element)))

(defn render-app
  "Render the app if root element exists"
  []
  (when root
    (js/console.log "dispatching init-db")
    (rf/dispatch-sync [:app/init-db system-data])
    (uix.dom/render-root ($ app) root)))

(defn ^:export init []
  (.on htmx "htmx:load"
       (fn [_]
         (render-app)
         (let [version (.-version htmx)]
           (js/console.log "HTMX loaded! Version:" version)))))

(defn ^:dev/after-load reload! []
  (js/console.log "Reloading app...")
  (uix.dom/render-root ($ app) root))
