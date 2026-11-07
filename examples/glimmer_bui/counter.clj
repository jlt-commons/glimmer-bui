(ns glimmer-bui.counter
  "The classic counter, Blender-style: a number slider showing the count and
  three buttons. Verifies the full stack live — ratom -> reconciler -> kit
  nodes -> BKWindow render."
  (:require [glimmer.ratom :refer [atom]]
            [glimmer.core :as ui]
            [glimmer-bui.core]))

(defonce count* (atom 0))

(defn counter []
  [:vbox {:padding 12}
   [:slider {:label "count" :min -100 :max 100 :value @count*}]
   [:hbox {}
    [:button {:text "- 1" :on-click #(swap! count* dec)}]
    [:button {:text "+ 1" :on-click #(swap! count* inc)}]
    [:button {:text "reset" :on-click #(reset! count* 0)}]]])

(defn -main [& _]
  (ui/run counter :title "counter" :width 320 :height 160))
