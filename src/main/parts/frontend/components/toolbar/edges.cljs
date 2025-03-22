(ns parts.frontend.components.toolbar.edges
  (:require
   [parts.frontend.components.edge-form :refer [edge-form]]
   [parts.frontend.components.toolbar.header :refer [header]]
   [parts.frontend.context :as ctx]
   [uix.core :refer [$ defui use-context]]))

(defui edges-tools
  "Renders a tool palette that displays the selected edges for editing"
  [{:keys [selected-edges]}]
  (let [{:keys [update-edge]} (use-context ctx/update-system-context)
        edge-count (count selected-edges)
        multiple-edges (> edge-count 1)]
    (when (seq selected-edges)
      ($ :div {:class "tools edge-tools"}
        ($ header {:title "Selected relationships" :count edge-count})
        ($ :section {:class "selected-edges"}
           (map
             (fn [edge]
               ($ edge-form {:key (str (:id edge) edge-count)
                             :edge edge
                             :collapsed multiple-edges
                             :on-save (fn [id form-data]
                                        (when update-edge
                                          (update-edge id form-data)))}))
             selected-edges))))))
