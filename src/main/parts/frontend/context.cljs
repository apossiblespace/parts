(ns parts.frontend.context
  (:require [uix.core]))

(def update-node-context (uix.core/create-context nil))

(def auth-context (uix.core/create-context 
                    {:logged-in false
                     :email nil
                     :login nil
                     :logout nil}))
