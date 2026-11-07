(ns glimmer-bui.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer-bui.widget :as w]
            [glimmer-bui.core :as c]
            [glimmer-bui.ffi :as bk]))
;; Headless: no window host, no context render. The context/emit dispatch is
;; exercised with direct BK_context calls.

(deftest emit-dispatch
  (testing "an emit with a handler attached to the node invokes it"
    (let [result (atom nil)
          n (w/create! :button {:text "b" :on-click (fn [] (reset! result :clicked))})
          root (bk/node-new "column")
          _ (bk/node-add root n -1)
          ctx (bk/context-new root)]
      (c/test-emit ctx n 2 nil 0.0 0.0) ; BK_EV_CLICK
      (is (= :clicked @result))
      (bk/context-free ctx)
      (bk/node-free root)))) ; frees n recursively

(deftest emit-with-value
  (testing "on-change receives the node's current value"
    (let [result (atom nil)
          n (w/create! :slider {:min 0 :max 10 :value 2.5 :on-change (fn [v] (reset! result v))})
          root (bk/node-new "column")
          _ (bk/node-add root n -1)
          ctx (bk/context-new root)]
      (c/test-emit ctx n 1 nil 0.0 0.0) ; BK_EV_CHANGE
      (is (= 2.5 @result))
      (bk/context-free ctx)
      (bk/node-free root)))) ; frees n recursively

(deftest emit-wheel
  (testing "on-wheel receives the delta"
    (let [result (atom nil)
          n (w/create! :canvas {:on-wheel (fn [d] (reset! result d))})
          root (bk/node-new "column")
          _ (bk/node-add root n -1)
          ctx (bk/context-new root)]
      (c/test-emit ctx n 15 nil -3.0 0.0) ; BK_EV_WHEEL, a = delta
      (is (= -3.0 @result))
      (bk/context-free ctx)
      (bk/node-free root))))
