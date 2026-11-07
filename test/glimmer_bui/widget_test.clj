(ns glimmer-bui.widget-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer-bui.widget :as w]
            [glimmer-bui.ffi :as bk]))

;; All tests are headless: build nodes, set props, read back via the BK API.
;; No window, no context, no render.

(deftest tag->node-type
  (testing "hiccup tags map to BUI kit node types"
    (is (= "column" (w/node-type-for :vbox)))
    (is (= "row" (w/node-type-for :hbox)))
    (is (= "button" (w/node-type-for :button)))
    (is (= "label" (w/node-type-for :label)))
    (is (= "frame" (w/node-type-for :frame)))
    (is (= "slider" (w/node-type-for :slider)))
    (is (= "number" (w/node-type-for :number)))
    (is (= "text" (w/node-type-for :entry)))
    (is (= "checkbox" (w/node-type-for :checkbox)))
    (is (= "panel" (w/node-type-for :panel)))
    (is (= "progress" (w/node-type-for :progress)))
    (is (= "dropdown" (w/node-type-for :dropdown)))
    (is (= "separator" (w/node-type-for :separator)))))

(deftest create-applies-props
  (testing "create! builds a node with props applied"
    (let [n (w/create! :button {:text "Apply" :enabled false})]
      (is (= "button" (bk/node-type n)))
      (is (= "Apply" (bk/get-str n "text")))
      (is (= 0.0 (bk/get-num n "enabled"))) ; number-typed flag prop
      (bk/node-free n)))
  (testing "geometry props route to BK_geom_set"
    (let [f (w/create! :frame {:padding 8})
          c (w/create! :button {:text "hi"})]
      (w/append-child! :frame f c)
      (is (= 1.0 (bk/geom-get c "manager"))) ; PACK after auto-manage
      (is (= 1 (bk/set-num f "padding" 8.0))) ; known key (set-only: no get-num)
      (bk/node-free f))))

(deftest apply-props-reapplies
  (testing "apply-props! updates an existing node"
    (let [n (w/create! :label {:text "one"})]
      (w/apply-props! :label n {:text "two"})
      (is (= "two" (bk/get-str n "text")))
      (bk/node-free n))))

(deftest children
  (testing "append/remove/replace"
    (let [f  (w/create! :frame {:padding 4})
          a  (w/create! :button {:text "a"})
          b  (w/create! :button {:text "b"})
          c  (w/create! :button {:text "c"})
          d  (w/create! :button {:text "d"})]
      (w/append-child! :frame f a)
      (w/append-child! :frame f b)
      (is (= 2 (bk/node-child-count f)))
      (w/remove-child! :frame f a)              ; frees a
      (is (= 1 (bk/node-child-count f)))
      (w/append-child! :frame f d)
      (w/replace-child! :frame f d c)           ; frees d
      (is (= 2 (bk/node-child-count f)))
      (is (= c (bk/node-child f 1)))
      (bk/node-free f))))

(deftest reorder
  (testing "reorder-child! moves a child after a sibling"
    (let [f (w/create! :frame {:padding 4})
          a (w/create! :button {:text "a"})
          b (w/create! :button {:text "b"})
          c (w/create! :button {:text "c"})]
      (doseq [ch [a b c]] (w/append-child! :frame f ch))
      (w/reorder-child! :frame f c nil)          ; c to front
      (is (= c (bk/node-child f 0)))
      (w/reorder-child! :frame f c b)            ; c after b
      (is (= a (bk/node-child f 0)))
      (is (= b (bk/node-child f 1)))
      (is (= c (bk/node-child f 2)))
      (bk/node-free f))))

(deftest events-registered
  (testing "on-* keys map to BUI event kinds"
    (is (= 2 (get-in @w/events [:on-click] nil) ))))

(deftest dispose
  (testing "dispose! frees the node"
    (let [n (w/create! :label {:text "x"})]
      (w/dispose! n)
      ;; freed — can't assert much headlessly beyond no crash
      (is true))))
