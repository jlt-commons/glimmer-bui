(ns glimmer-bui.smoke
  "Reactivity smoke against the live window loop: run a counter app with
  :auto-quit-ms, assert frames were presented, exit non-zero otherwise."
  (:require [glimmer.ratom :refer [atom]]
            [glimmer.core :as ui]
            [glimmer-bui.core]))

(defonce count* (atom 0))

(defn counter []
  [:vbox {:padding 12}
   [:slider {:label "count" :min 0 :max 100 :value @count*}]
   [:hbox {}
    [:button {:text "- 1" :on-click #(swap! count* dec)}]
    [:button {:text "+ 1" :on-click #(swap! count* inc)}]]])

(defn -main [& _]
  (let [armed? (atom false)]
    (ui/run counter :title "smoke" :width 320 :height 160 :auto-quit-ms 800
            :on-timer (fn []
                        (if @armed?
                          nil                       ; second tick: quit
                          (do (reset! armed? true)
                              (swap! count* + 25)   ; reactive re-render mid-run
                              700)))))              ; re-arm once
  (let [frames @glimmer-bui.core/last-frames]
    (if (and frames (> frames 1))
      (println "smoke ok:" frames "frames (reactive re-render presented)")
      (do (println "SMOKE FAILED: frames =" frames)
          (System/exit 1)))))
