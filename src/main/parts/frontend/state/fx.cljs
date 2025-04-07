(ns parts.frontend.state.fx
  (:require
   [parts.frontend.api.queue :as queue]
   [re-frame.core :as rf]))

(rf/reg-fx
 :queue/add-event
 (fn [event]
   (queue/add-events!
    (:entity event)
    [event])))
