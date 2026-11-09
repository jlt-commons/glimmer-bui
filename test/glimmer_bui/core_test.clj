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

(defn- emit-on [props kind a b]
  (let [result (atom nil)
        n (w/create! :canvas (assoc props :events 1))
        root (bk/node-new "column")
        _ (bk/node-add root n -1)
        ctx (bk/context-new root)]
    (c/test-emit ctx n kind nil a b)
    (bk/context-free ctx)
    (bk/node-free root)
    result))

(deftest emit-pointer-positions
  (testing "raw pointer events pass the node-relative position (a, b)"
    (doseq [[k kind] [[:on-press 8] [:on-release 9] [:on-double 10] [:on-motion 11]]]
      (let [seen (atom nil)]
        @(emit-on {k (fn [x y] (reset! seen [k x y]))} kind 12.0 34.0)
        (is (= [k 12.0 34.0] @seen)))))
  (testing "configure passes the new width and height"
    (let [seen (atom nil)]
      (emit-on {:on-configure (fn [w h] (reset! seen [w h]))} 19 640.0 480.0)
      (is (= [640.0 480.0] @seen)))))

(deftest drain-isolates-failures
  (testing "a throwing re-render doesn't drop the work queued after it"
    (let [ran (atom [])]
      (swap! @#'c/work-queue into [#(swap! ran conj 1)
                                   #(throw (ex-info "boom" {}))
                                   #(swap! ran conj 3)])
      (binding [*err* (java.io.StringWriter.)]
        (#'c/drain-work!))
      (is (= [1 3] @ran)))))
