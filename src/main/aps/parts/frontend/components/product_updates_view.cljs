(ns aps.parts.frontend.components.product-updates-view
  "Pure logic for the Product-updates question on the Maps list. It has no
   uix or React, so the cljs suite can test it directly.")

(defn prompt
  "What the Maps list shows about Product updates for `user`:

   - `:ask` when the User never chose (accounts from before the in-app
     question) and has not clicked a button on this page yet.
   - `:subscribed` after the User clicked Subscribe here and the server
     has recorded it.
   - nil otherwise.

   `answered` is the button clicked on this page: `:yes`, `:no` or nil."
  [user answered]
  (let [chosen?     (some? (:product_updates_chosen_at user))
        subscribed? (nil? (:product_updates_opted_out_at user))]
    (cond
      (nil? user)                                 nil
      (and (nil? answered) (not chosen?))         :ask
      (and (= answered :yes) chosen? subscribed?) :subscribed)))
