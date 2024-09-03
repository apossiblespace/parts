(ns apossiblespace.parts.account
  (:require
   [com.brunobonacci.mulog :as mulog]))

(defn get-account
  "Retrieve own account info"
  []
  {:success "GET account"})

(defn update-account
  "Update own account info"
  [account-data]
  {:success "PUT account"})

(defn delete-account
  "Delete own account"
  [confirm]
  {:success "DELETE account"})
