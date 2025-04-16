(ns parts.api.systems-events
  (:require
   [com.brunobonacci.mulog :as mulog]
   [parts.entity.relationship :as relationship]
   [parts.entity.part :as part]))

(defmulti process-change
  "Process a single change event based on entity type and operation"
  (fn [_system-id event]
    [(keyword (:entity event)) (keyword (:type event))]))

(defmethod process-change [:part :create]
  [system-id {:keys [id data]}]
  (try
    (let [part-data (assoc data
                           :id id
                           :system_id system-id)
          created (part/create! part-data)]
      {:success true
       :result created})
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defmethod process-change [:part :update]
  [_system-id {:keys [id data]}]
  (try
    (let [updated (part/update! id data)]
      {:success true
       :result updated})
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defmethod process-change [:part :position]
  [_system-id {:keys [id data]}]
  (try
    (let [position-data {:position_x (int (:x data))
                         :position_y (int (:y data))}
          updated (part/update! id position-data)]
      {:success true
       :result updated})
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defmethod process-change [:part :remove]
  [_system-id {:keys [id]}]
  (try
    (let [result (part/delete! id)]
      {:success (:deleted result)
       :result result})
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defmethod process-change [:relationship :create]
  [system-id {:keys [id data]}]
  (try
    (let [rel-data (assoc data
                          :id id
                          :system_id system-id)
          created (relationship/create! rel-data)]
      {:success true
       :result created})
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defmethod process-change [:relationship :update]
  [_system-id {:keys [id data]}]
  (try
    (let [updated (relationship/update! id data)]
      {:success true
       :result updated})
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defmethod process-change [:relationship :remove]
  [_system-id {:keys [id]}]
  (try
    (let [result (relationship/delete! id)]
      {:success (:deleted result)
       :result result})
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defmethod process-change :default
  [_system-id event]
  (mulog/log ::unknown-change-type :event event)
  {:success false
   :error (str "Unknown change type: " (:entity event) "/" (:type event))})
