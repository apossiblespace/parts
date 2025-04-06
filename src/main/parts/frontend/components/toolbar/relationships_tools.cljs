(ns parts.frontend.components.toolbar.relationships-tools
  (:require
   [parts.frontend.components.toolbar.relationship-form :refer [relationship-form]]
   [parts.frontend.components.toolbar.header :refer [header]]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as uix.rf]
   [re-frame.core :as rf]))

(defui relationships-tools
  "Renders a tool palette that displays the selected relationships for editing"
  []
  (let [selected-relationships (uix.rf/use-subscribe [:system/selected-relationships])
        relationship-count (count selected-relationships)
        multiple-relationships (> relationship-count 1)]
    (when (seq selected-relationships)
      ($ :div {:class "tools relationships-tools"}
         ($ header {:title "Selected relationships" :count relationship-count})
         ($ :section {:class "selected-relationships"}
            (map
             (fn [relationship]
               ($ relationship-form {:key (str (:id relationship) relationship-count)
                                     :relationship relationship
                                     :collapsed multiple-relationships
                                     :on-save (fn [id updated-attrs]
                                                (rf/dispatch [:system/relationship-update id updated-attrs]))}))
             selected-relationships))))))
