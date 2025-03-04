(ns parts.frontend.api.core
  (:require [cljs.core.async :refer [chan put! <! go go-loop timeout]]
            [cljs-http.client :as http]
            [parts.frontend.utils.api :as utils]
            [parts.frontend.state :as state]))

;; Core channels: request, response, events
(def request-channel (chan 10))
(def response-channel (chan 10))
(def event-channel (chan 10))

(def request-types
  {:fetch-data              {:method :get}
   :create-entity           {:method :post}
   :update-entity           {:method :put}
   :partially-update-entity {:method :patch}
   :delete-entity           {:method :delete}
   ;; Example:
   ;; :search {:method :get, :throttle 300}
   })

(defn- build-request [req-type endpoint params]
  (let [base-config (get request-types req-type)
        method (:method base-config)
        headers {"Authorization" (utils/get-auth-header)
                 "Content-Type" "application/json"}]
    {:method method
     :url (str "/api" endpoint)
     :headers headers
     :params params
     :type req-type}))

(defn- start-request-manager []
  (go-loop []
    (let [request (<! request-channel)
          {:keys [method url headers params type]} request]
      (println "[request]" request)
      (when-let [throttle-ms (get-in request-types [type :throttle])]
        (<! (timeout throttle-ms)))

      (go
        (try
          (let [response (<! (case method
                               :get (http/get url {:headers headers :query-params params})
                               :post (http/post url {:headers headers :json-params params})
                               :put (http/put url {:headers headers :json-params params})
                               :patch (http/patch url {:headers headers :json-params params})
                               :delete (http/delete url {:headers headers})))]
            (if (< (:status response) 400)
              (put! response-channel {:type type
                                     :request request
                                     :response response
                                     :status :success})
              (put! response-channel {:type type
                                     :request request
                                     :error {:message (str "HTTP Error: " (:status response))
                                            :response response}
                                     :status :error})))
          (catch js/Error e
            (put! response-channel {:type type
                                    :request request
                                    :error e
                                    :status :error}))))
      (recur))))

(defn- start-response-handler []
  (go-loop []
    (let [{:keys [type request response error status]} (<! response-channel)]
      (cond
        (= status :success)
        (let [data (-> response :body)]
          (case type
            :fetch-data    (state/set-entities! data)
            :create-entity (state/add-entity! data)
            :update-entity (state/update-entity! data)
            :delete-entity (state/remove-entity! (:id (:params request))))

          (put! event-channel {:event :api-success
                               :type type
                               :data data}))
        (= status :error)
        (let [error-data {:message (or (-> error .-message) "Unknown error")
                          :type type
                          :request request}]
          (state/set-error! error-data)
          (put! event-channel {:event :api-error
                               :error error-data})))
      (recur))))

(defn fetch-data [endpoint & [params]]
  (put! request-channel (build-request :fetch-data endpoint params))
  (let [result-channel (chan)]
    (go
      (loop []
        (let [event (<! event-channel)]
          (println "fetch-data" event)
          (if (and (= (:event event) :api-success)
                   (= (:type event) :fetch-data))
            (put! result-channel (:data event))
            (recur)))))
    result-channel))

(defn create-entity [endpoint entity]
  (put! request-channel (build-request :create-entity endpoint entity)))

(defn update-entity [endpoint entity]
  (put! request-channel (build-request :update-entity endpoint entity)))

(defn delete-entity [endpoint id]
  (put! request-channel (build-request :delete-entity endpoint {:id id})))

(defn init! []
  (start-request-manager)
  (start-response-handler)
  (println "API layer initialised"))
