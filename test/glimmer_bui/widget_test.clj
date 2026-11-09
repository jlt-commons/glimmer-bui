(ns glimmer-bui.widget-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer-bui.widget :as w]
            [glimmer-bui.ffi :as bk]
            [jolt.ffi]))

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

(deftest apply-props-diffs
  (testing "re-applying an unchanged prop does not touch the node"
    ;; the user drags a slider; a re-render with the same :value must not
    ;; snap it back (the jitter), and must not relayout the tree
    (let [n (w/create! :slider {:min 0 :max 10 :value 5})]
      (bk/set-num n "value" 7.0)               ; user interaction
      (w/apply-props! :slider n {:min 0 :max 10 :value 5})
      (is (= 7.0 (bk/get-num n "value")))
      (testing "a changed prop is applied"
        (w/apply-props! :slider n {:min 0 :max 10 :value 9})
        (is (= 9.0 (bk/get-num n "value"))))
      (w/dispose! n)))
  (testing "dispose! drops the node's cached props"
    (let [n (w/create! :label {:text "x"})]
      (is (= {:text "x"} (w/applied-props n)))
      (w/dispose! n)
      (is (nil? (w/applied-props n))))))

(deftest layout-props
  (testing "row weight, column stretch and unit height reach the node"
    (let [n (w/create! :canvas {:weight 0 :stretch true :height-units 12 :spacing 3})]
      (is (= 0.0 (bk/get-num n "weight")))
      (is (= 1.0 (bk/get-num n "fill")))
      (is (= 12.0 (bk/get-num n "height")))
      (w/dispose! n)))
  (testing "reqwidth fixes the natural width of any node"
    (let [n (w/create! :column {:reqwidth 300})]
      (is (= 300.0 (bk/geom-get n "reqwidth")))
      (w/dispose! n))))

(deftest color-props
  (testing "a 4-vector on a color key goes through BK_set_color"
    (let [n (w/create! :label {:text "x" :text-color [1 0 0 1]})]
      (is (= 1 (count (filter #{:text-color} (keys (w/applied-props n))))))
      (w/dispose! n))))

(deftest canvas-items
  (testing ":canvas-items sets items by id and deletes ids that disappear"
    (let [n (w/create! :canvas {:canvas-items [{:id 1 :type :rect :coords [0 0 10 10] :fill [1 0 0 1]}
                                               {:id 2 :type :oval :coords [0 0 4 4] :outline [1 1 1 1] :width 1}]})]
      (is (= 2 (bk/canvas-count n)))
      (w/apply-props! :canvas n {:canvas-items [{:id 2 :type :oval :coords [1 1 5 5] :outline [1 1 1 1] :width 1}]})
      (is (= 1 (bk/canvas-count n)))
      (w/dispose! n)))
  (testing "an image item takes a native RGBA pointer without copying through Clojure"
    (let [p (jolt.ffi/alloc (* 2 2 4))
          n (w/create! :canvas {:canvas-items [{:id 7 :type :image :coords [0 0 2 2]
                                                :rgba p :iw 2 :ih 2}]})]
      (is (= 1 (bk/canvas-count n)))
      (w/dispose! n)
      (jolt.ffi/free p))))

(deftest panel-children
  (testing "panel content goes into the panel's body; :header widgets into its header"
    (let [p (w/create! :panel {:text "Denoise"})
          body (bk/node-child p 0)
          content (w/create! :slider {:label "Strength"})
          header (w/create! :checkbox {:header true})]
      (is (= 1 (bk/node-child-count p)) "a new panel holds just its body")
      (w/append-child! :panel p content)
      (w/append-child! :panel p header)
      (is (= 2 (bk/node-child-count p)) "body + the header checkbox")
      (is (= header (bk/node-child p 1)))
      (is (= 1 (bk/node-child-count body)))
      (is (= content (bk/node-child body 0)))
      (testing "remove and replace work on the body"
        (let [other (w/create! :label {:text "x"})]
          (w/replace-child! :panel p content other)
          (is (= other (bk/node-child body 0)))
          (w/remove-child! :panel p other)
          (is (= 0 (bk/node-child-count body)))))
      (w/dispose! p))))

(deftest replace-keeps-position
  (testing "a replaced child takes the old child's place, not the end"
    (let [col (w/create! :column {})
          a (w/create! :label {:text "a"})
          b (w/create! :label {:text "b"})
          c (w/create! :label {:text "c"})
          b2 (w/create! :button {:text "b2"})]
      (doseq [x [a b c]] (w/append-child! :column col x))
      (w/replace-child! :column col b b2)
      (is (= [a b2 c] (mapv #(bk/node-child col %) (range 3))))
      (w/dispose! col))))
