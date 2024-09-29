(ns tools.ifs.parts.layouts.partials)

(defn header
  "Site header"
  []
  [:header
   [:p {:align "center"}
    [:img {:src "/images/parts-logo-horizontal.svg"}]]])

(defn footer
  "Site footer"
  []
  [:footer
   [:div.copyright
    [:p
     "&copy; 2024 "
     [:a {:href "https://a.possible.space"} "A Possible Space Ltd"]
     [:br]
     "Company number 11617016"]]
   [:div.meta
    [:p
     [:strong "Parts"]
     " is free, open source software."
     [:br]
     "See the "
     [:a {:href "https://github.com/apossiblespace/parts"} "source code on GitHub"]
     "."]]])
