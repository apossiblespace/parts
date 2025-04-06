(ns parts.frontend.components.toolbar.parts-tools
  (:require
   [parts.frontend.components.toolbar.part-form :refer [part-form]]
   [parts.frontend.components.toolbar.header :refer [header]]
   [uix.core :refer [$ defui]]
   [uix.re-frame :as uix.rf]
   [re-frame.core :as rf]))

(defui parts-tools
  "Renders a tool palette that displays the selected Parts for editing"
  []
  (let [selected-parts (uix.rf/use-subscribe [:system/selected-parts])
        part-count (count selected-parts)
        multiple-parts (> part-count 1)]
    (when (seq selected-parts)
      ($ :div {:class "tools parts-tools"}
         ($ header {:title "Selected parts" :count part-count})
         ($ :section {:class "selected-parts"}
            (map
             (fn [part]
               ($ part-form {:key (str (:id part) part-count)
                             :part part
                             :collapsed multiple-parts
                             :on-save (fn [id updated-attrs]
                                        (rf/dispatch [:system/part-update id updated-attrs]))}))
             selected-parts))))))
